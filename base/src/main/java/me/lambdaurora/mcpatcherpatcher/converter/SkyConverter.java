/*
 *  Copyright (c) 2020 LambdAurora <aurora42lambda@gmail.com>
 *
 *  This program is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU Lesser General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU Lesser General Public License for more details.
 *
 *  You should have received a copy of the GNU Lesser General Public License
 *  along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package me.lambdaurora.mcpatcherpatcher.converter;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import me.lambdaurora.mcpatcherpatcher.Closeable;
import me.lambdaurora.mcpatcherpatcher.ErrorType;
import me.lambdaurora.mcpatcherpatcher.ResourceType;
import me.lambdaurora.mcpatcherpatcher.fs.ResourceAccessor;
import me.lambdaurora.mcpatcherpatcher.image.BasicImage;
import me.lambdaurora.mcpatcherpatcher.image.ImageProvider;
import me.lambdaurora.mcpatcherpatcher.mcpatcher.MCPatcherParser;
import org.aperlambda.lambdacommon.Identifier;
import org.aperlambda.lambdacommon.LambdaConstants;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Represents the converter for Custom Sky Boxes.
 * <p>
 * Converts to the fabricskyboxes (FSB) format.
 *
 * @author FlashyReese
 * @version 1.0.0
 * @since 1.0.0
 */
public class SkyConverter extends Converter implements Closeable
{
    public static final String  FABRICSKYBOXES_NAMESPACE = "fabricskyboxes";
    public static final String  FABRICSKYBOXES_PARENT    = "sky";
    public static final String  SKY_PARENT               = "optifine/sky";
    public static final Pattern SKY_PATTERN              = Pattern.compile("optifine/sky/(?<world>\\w+)/(?<name>\\w+).properties$");

    private final Map<Identifier, byte[]> cached = new HashMap<>();

    public SkyConverter(@NotNull ResourceAccessor input, @NotNull ResourceAccessor output)
    {
        super(input, output);
    }

    @Override
    public @NotNull Map<Identifier, ErrorType> convert(@NotNull ImageProvider imageProvider)
    {
        Map<Identifier, ErrorType> failed = new HashMap<>();

        this.input.getNamespaces(ResourceType.ASSETS).stream()
                .map(namespace -> new Identifier(namespace, SKY_PARENT))
                .forEach(parent -> this.input.searchIn(ResourceType.ASSETS, parent)
                        .filter(id -> id.getName().endsWith(".properties"))
                        .forEach(id -> {
                            Matcher matcher = SKY_PATTERN.matcher(id.getName());
                            if (matcher.find()) {
                                String dimension = matcher.group("world");
                                String name = matcher.group("name");

                                if (dimension == null || name == null || !dimension.equals("world0")) // This is temporary just don't convert any non-OW dimensions for time being
                                    return;

                                Identifier fsbId = new Identifier(FABRICSKYBOXES_NAMESPACE, String.format("%s/%s.json", FABRICSKYBOXES_PARENT, name));

                                InputStream inputStream = this.input.getInputStream(ResourceType.ASSETS, id);
                                if (inputStream == null) {
                                    failed.put(id, ErrorType.INPUTSTREAM_IO);
                                    return;
                                }

                                Properties properties = new Properties();
                                try {
                                    properties.load(inputStream);
                                } catch (IOException e) {
                                    failed.put(id, ErrorType.PROPERTIES_READ);
                                    return;
                                } finally {
                                    try {
                                        inputStream.close();
                                    } catch (IOException e) {
                                        e.printStackTrace();
                                    }
                                }

                                if (properties.size() == 0) {
                                    return;
                                }

                                Identifier textureId;
                                if (properties.containsKey("source")) {
                                    String source = properties.getProperty("source");
                                    if (source.startsWith("./")) {
                                        textureId = new Identifier(id.getNamespace(), parent.getName() + String.format("/%s/%s", dimension, source.substring(2)));
                                    } else if (source.startsWith("assets/")) {
                                        int firstIndex = source.indexOf("/") + 1;
                                        int secondIndex = source.indexOf("/", firstIndex);
                                        String sourceNamespace = source.substring(firstIndex, secondIndex);
                                        textureId = new Identifier(sourceNamespace, source.substring(secondIndex + 1));
                                    } else {
                                        //There is no way you're in another ResourceType and if you are >:(, this will not work inputStream still reads from ASSETS
                                        int firstIndex = source.indexOf("/") + 1;
                                        String sourceNamespace = source.substring(0, firstIndex - 1);
                                        textureId = new Identifier(sourceNamespace, source.substring(firstIndex));
                                    }
                                } else {
                                    textureId = new Identifier(id.getNamespace(), parent.getName() + String.format("/%s/%s.png", dimension, name));
                                }

                                InputStream textureInputStream = this.input.getInputStream(ResourceType.ASSETS, textureId);
                                if (textureInputStream == null) {
                                    failed.put(textureId, ErrorType.INPUTSTREAM_IO);
                                    return;
                                }

                                BasicImage textureImage;
                                try {
                                    textureImage = imageProvider.readImage(textureInputStream);
                                } catch (IOException e) {
                                    failed.put(textureId, ErrorType.INPUTSTREAM_IO);
                                    return;
                                }

                                this.convert(fsbId, textureId, textureImage, properties, dimension);

                                textureImage.close();
                            }
                        }));

        return failed;
    }

    /**
     * Converts one MCPatcher file to FSB format.
     *
     * @param fsbId        The FSB metadata file identifier.
     * @param textureId    The texture file identifier.
     * @param textureImage The texture BasicImage
     * @param properties   The MCPatcher properties file.
     * @param dimension    The dimension name
     */
    private void convert(@NotNull Identifier fsbId, @NotNull Identifier textureId, @NotNull BasicImage textureImage, @NotNull Properties properties, @NotNull String dimension)
    {
        JsonObject json = null;
        if (properties.size() > 1) {
            json = new JsonObject();

            json.addProperty("type", "square-textured"); // "alright only thing you need to account for is literally insert "type": "textured" into the json" -AMereBagatelle
            json.addProperty("decorations", true); // "New thing to account for reese:  add "decorations": true on every skybox" -AMereBagatelle
            json.addProperty("shouldBlend", false);
            processSkyboxTexture(json, textureId, textureImage);

            int startFadeIn = Objects.requireNonNull(MCPatcherParser.toTickTime(properties.getProperty("startFadeIn"))).intValue();
            int endFadeIn = Objects.requireNonNull(MCPatcherParser.toTickTime(properties.getProperty("endFadeIn"))).intValue();
            int endFadeOut = Objects.requireNonNull(MCPatcherParser.toTickTime(properties.getProperty("endFadeOut"))).intValue();
            int startFadeOut;
            if (properties.containsKey("startFadeOut")) {
                startFadeOut = Objects.requireNonNull(MCPatcherParser.toTickTime(properties.getProperty("startFadeOut"))).intValue();
            } else {
                startFadeOut = endFadeOut - (endFadeIn - startFadeIn);
                if (startFadeIn <= startFadeOut && endFadeIn >= startFadeOut) {
                    startFadeOut = endFadeOut;
                }
            }
            json.addProperty("startFadeIn", MCPatcherParser.normalizeTickTime(startFadeIn));
            json.addProperty("endFadeIn", MCPatcherParser.normalizeTickTime(endFadeIn));
            json.addProperty("startFadeOut", MCPatcherParser.normalizeTickTime(startFadeOut));
            json.addProperty("endFadeOut", MCPatcherParser.normalizeTickTime(endFadeOut));

            if (properties.containsKey("rotate")) { //"@FlashyReese Did you forget to parse rotate= into "shouldRotate":?" -AMereBagatelle
                json.addProperty("shouldRotate", Boolean.parseBoolean(properties.getProperty("rotate")));
            }

            JsonArray jsonAxis = new JsonArray();
            if (properties.containsKey("axis")) {
                String[] axis = properties.getProperty("axis").split(" ");
                for (String a : axis) {
                    jsonAxis.add(Float.parseFloat(a) * 180);
                }
            } else {
                //Default South
                jsonAxis.add(0f);
                jsonAxis.add(0f);
                jsonAxis.add(180f);
            }
            json.add("axis", jsonAxis);

            if (properties.containsKey("speed")) {
                json.addProperty("transitionSpeed", Float.parseFloat(properties.getProperty("speed")));
            }

            if (properties.containsKey("weather")) {
                String[] weathers = properties.getProperty("weather").split(" ");
                if (weathers.length == 1) {
                    json.addProperty("weather", weathers[0]);
                } else {
                    JsonArray jsonWeather = new JsonArray();
                    for (String weather : weathers) {
                        jsonWeather.add(weather);
                    }
                    json.add("weather", jsonWeather);
                }
            }

            if (properties.containsKey("biomes")) {
                String[] biomes = properties.getProperty("biomes").split(" ");
                if (biomes.length == 1) {
                    json.addProperty("biomes", biomes[0]);
                } else {
                    JsonArray jsonBiomes = new JsonArray();
                    for (String biome : biomes) {
                        jsonBiomes.add(biome);
                    }
                    json.add("biomes", jsonBiomes);
                }
            }
            //FSB dimensions default is overworld, how should I check for existing json?
            json.addProperty("dimensions", dimension.equals("world0") ? "minecraft:overworld" : dimension);
        }

        if (json == null)
            return;

        String res = LambdaConstants.GSON_PRETTY.toJson(json);
        this.cached.put(fsbId, res.getBytes());
    }

    /**
     * Converts Optifine skybox textures to FSB format.
     *
     * @param json         The FSB JSON file.
     * @param textureId    The Skybox Texture Identifier file.
     * @param textureImage The Skybox Texture file.
     */
    private void processSkyboxTexture(@NotNull JsonObject json, @NotNull Identifier textureId, @NotNull BasicImage textureImage)
    {
        String textureName = textureId.getName().substring(textureId.getName().lastIndexOf("/") + 1, textureId.getName().lastIndexOf("."));
        int scale = textureImage.getHeight() / 2;
        this.processFaceTexture(json, textureName, "top", textureImage.getSubImage(scale, 0, scale, scale));
        this.processFaceTexture(json, textureName, "bottom", textureImage.getSubImage(0, 0, scale, scale));
        this.processFaceTexture(json, textureName, "north", textureImage.getSubImage(0, scale, scale, scale));
        this.processFaceTexture(json, textureName, "south", textureImage.getSubImage(scale * 2, scale, scale, scale));
        this.processFaceTexture(json, textureName, "east", textureImage.getSubImage(scale, scale, scale, scale));
        this.processFaceTexture(json, textureName, "west", textureImage.getSubImage(scale * 2, 0, scale, scale));
    }

    /**
     * Generates new face textures.
     *
     * @param json        The FSB JSON file.
     * @param textureName The Name of Skybox Texture file.
     * @param face        The Name of Texture Face.
     * @param texture     The Texture Face file.
     */
    private void processFaceTexture(@NotNull JsonObject json, @NotNull String textureName, @NotNull String face, @NotNull BasicImage texture)
    {
        Identifier faceId = new Identifier(FABRICSKYBOXES_NAMESPACE, String.format("%s/%s.png", FABRICSKYBOXES_PARENT, String.format("%s_%s", textureName, face)));
        if (!this.cached.containsKey(faceId))
            this.cached.put(faceId, texture.getBytes());
        json.addProperty(String.format("texture_%s", face), faceId.toString());
    }


    @Override
    public void close()
    {
        for (Map.Entry<Identifier, byte[]> entry : this.cached.entrySet()) {
            Identifier identifier = entry.getKey();
            byte[] out = entry.getValue();
            this.output.put(ResourceType.ASSETS, identifier, out);
        }

        this.cached.clear();
    }

    @Override
    public @NotNull String getName()
    {
        return "Sky";
    }
}
