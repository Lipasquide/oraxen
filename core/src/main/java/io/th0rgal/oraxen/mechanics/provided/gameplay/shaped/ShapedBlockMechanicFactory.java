// oraxen-master/core/src/main/java/io/th0rgal/oraxen/mechanics/provided/gameplay/shaped/ShapedBlockMechanicFactory.java

package io.th0rgal.oraxen.mechanics.provided.gameplay.shaped;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.th0rgal.oraxen.OraxenPlugin;
import io.th0rgal.oraxen.config.Settings;
import io.th0rgal.oraxen.mechanics.ConfigProperty;
import io.th0rgal.oraxen.mechanics.Mechanic;
import io.th0rgal.oraxen.mechanics.MechanicFactory;
import io.th0rgal.oraxen.mechanics.MechanicInfo;
import io.th0rgal.oraxen.mechanics.MechanicsManager;
import io.th0rgal.oraxen.mechanics.PropertyType;
import io.th0rgal.oraxen.utils.logs.Logs;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@MechanicInfo(
    category = "gameplay",
    description = "Allows creating custom shaped blocks (stairs, slabs, doors, fence, walls, buttons, etc.) using standard vanilla block types as base"
)
public class ShapedBlockMechanicFactory extends MechanicFactory {

    private static ShapedBlockMechanicFactory instance;

    // Map of Material -> Mechanic for quick lookup
    private final Map<Material, ShapedBlockMechanic> mechanicByMaterial = new HashMap<>();

    // Store model names for blockstate generation
    private final Map<Material, String> modelByMaterial = new HashMap<>();

    // Store texture info for generating variant models
    private final Map<Material, List<String>> texturesByMaterial = new HashMap<>();

    // Store parent model overrides
    private final Map<Material, String> parentModelByMaterial = new HashMap<>();

    // Store block type for each material
    private final Map<Material, ShapedBlockType> typeByMaterial = new HashMap<>();

    private final List<String> toolTypes;

    @ConfigProperty(type = PropertyType.STRING, description = "Type of shaped block: STAIR, SLAB, DOOR, TRAPDOOR, GRATE, BULB, FENCE, FENCE_GATE, WALL, BUTTON, PRESSURE_PLATE")
    public static final String PROP_TYPE = "type";
    @ConfigProperty(type = PropertyType.INTEGER, description = "Custom variation (1-n, maps to available materials)", defaultValue = "1", min = 1)
    public static final String PROP_CUSTOM_VARIATION = "custom_variation";

    @ConfigProperty(type = PropertyType.INTEGER, description = "Block hardness for mining", defaultValue = "3", min = -1)
    public static final String PROP_HARDNESS = "hardness";

    public ShapedBlockMechanicFactory(ConfigurationSection section) {
        super(section);
        instance = this;
        toolTypes = section.getStringList("tool_types");

        // Register pack modifier to generate blockstate files after all items are parsed
        OraxenPlugin.get().getResourcePack().addModifiers(getMechanicID(), packFolder -> {
            generateBlockstates();
        });

        MechanicsManager.registerListeners(OraxenPlugin.get(), getMechanicID(),
            new ShapedBlockMechanicListener(this));

        if (Settings.DEBUG.toBool()) {
            Logs.logSuccess("ShapedBlockMechanicFactory initialized with tool types: " + toolTypes);
        }
    }

    public static ShapedBlockMechanicFactory getInstance() {
        return instance;
    }

    @Override
    public Mechanic parse(ConfigurationSection section) {
        ShapedBlockMechanic mechanic = new ShapedBlockMechanic(this, section);

        // Register the mechanic by material
        mechanicByMaterial.put(mechanic.getPlacedMaterial(), mechanic);

        // Store model name for blockstate generation
        String modelName = mechanic.getModel(section.getParent().getParent());
        if (Settings.DEBUG.toBool()) {
            Logs.logInfo("Shaped block " + mechanic.getItemID() + " model: " + modelName);
        }
        if (modelName != null) {
            modelByMaterial.put(mechanic.getPlacedMaterial(), modelName);
            typeByMaterial.put(mechanic.getPlacedMaterial(), mechanic.getBlockType());
            if (Settings.DEBUG.toBool()) {
                Logs.logInfo("Stored model for " + mechanic.getPlacedMaterial() + " -> " + modelName);
            }

            // Store textures for generating variant models
            List<String> blockTextures = getBlockTextures(section, mechanic.getBlockType());            if (blockTextures.isEmpty()) {
                ConfigurationSection packSection = section.getParent().getParent().getConfigurationSection("Pack");
                if (packSection != null) {
                    blockTextures = new ArrayList<>(packSection.getStringList("textures"));
                }
            }
            if (!blockTextures.isEmpty()) {
                if (Settings.DEBUG.toBool()) {
                    Logs.logInfo("Storing textures for " + mechanic.getPlacedMaterial() + ": " + blockTextures);
                }
                texturesByMaterial.put(mechanic.getPlacedMaterial(), blockTextures);
            }

            String parentModel = section.getParent().getParent().getString("Pack.parent_model");
            if (parentModel != null && !parentModel.isBlank()) {
                parentModelByMaterial.put(mechanic.getPlacedMaterial(), parentModel);
            }
        } else {
            Logs.logWarning("No model found for shaped block " + mechanic.getItemID());
        }

        addToImplemented(mechanic);
        if (Settings.DEBUG.toBool()) {
            Logs.logSuccess("Registered shaped block item: " + mechanic.getItemID() + " -> " +
                mechanic.getBlockType() + " variation " + mechanic.getCustomVariation() + " (" + mechanic.getPlacedMaterial() + ")");
        }
        return mechanic;
    }

    private List<String> getBlockTextures(ConfigurationSection section, ShapedBlockType type) {
        List<String> textures = new ArrayList<>();
        ConfigurationSection texturesSection = section.getConfigurationSection("textures");
        if (texturesSection != null) {
            // Example: Could have specific keys like 'post', 'side' for fences/walls, 'all' for buttons/plates
            String texture = texturesSection.getString("texture");
            if (texture == null) texture = texturesSection.getString("all");
            if (texture != null) textures.add(texture);
        }
        if (textures.isEmpty() && section.isList("textures")) {
            textures.addAll(section.getStringList("textures"));
        }
        return textures;
    }

    private void generateBlockstates() {
        if (Settings.DEBUG.toBool()) {
            Logs.logInfo("Generating blockstates for " + modelByMaterial.size() + " shaped blocks");
        }
        for (Map.Entry<Material, String> entry : modelByMaterial.entrySet()) {
            Material material = entry.getKey();            String modelName = entry.getValue();
            List<String> textures = texturesByMaterial.get(material);
            ShapedBlockType type = typeByMaterial.get(material);
            String parentModelOverride = parentModelByMaterial.get(material);

            if (textures != null && !textures.isEmpty() && type != null) {
                generateVariantModels(type, modelName, textures, parentModelOverride);
            }

            String blockstateName = material.name().toLowerCase() + ".json";
            String blockstateContent = generateBlockstateForMaterial(material, modelName);

            OraxenPlugin.get().getResourcePack().writeStringToVirtual(
                "assets/minecraft/blockstates", blockstateName, blockstateContent);

            if (Settings.DEBUG.toBool()) {
                Logs.logSuccess("Generated blockstate for " + material + " -> " + modelName);
            }
        }
    }

    private void generateVariantModels(ShapedBlockType type, String modelName, List<String> textures, String parentModelOverride) {
        String primaryTexture = normalizeTexturePath(textures.get(0));
        generateBaseBlockModel(type, modelName, textures, parentModelOverride);

        // Add specific variant models here if needed (e.g., fence_post, fence_side, wall_post, wall_side)
        // For now, base or specific parent models do
    }

    private void generateBaseBlockModel(ShapedBlockType type, String modelName, List<String> textureList, String parentModelOverride) {
        JsonObject model = new JsonObject();
        JsonObject textures = new JsonObject();

        String primaryTexture = normalizeTexturePath(textureList.get(0));

        if (Settings.DEBUG.toBool()) {
            Logs.logInfo("Generating base block model " + modelName + " with texture: " + primaryTexture);
        }

        String parent = parentModelOverride;
        if (parent == null || parent.isBlank()) {
            parent = switch (type) {
                case STAIR -> "block/stairs";
                case SLAB -> "block/slab";
                case DOOR -> "block/door_bottom_left";
                case TRAPDOOR -> "block/template_trapdoor_bottom";
                case GRATE -> "block/cube_all"; // Example
                case BULB -> "block/cube_all"; // Example
                case FENCE -> "block/fence_post";
                case FENCE_GATE -> "block/template_fence_gate";                case WALL -> "block/wall_post";
                case BUTTON -> "block/button";
                case PRESSURE_PLATE -> "block/pressure_plate_up";
            };
        }
        model.addProperty("parent", parent);
        textures.addProperty("texture", primaryTexture);
        // Adjust based on parent model requirements (e.g., 'side', 'top', 'bottom')
        model.add("textures", textures);

        OraxenPlugin.get().getResourcePack().writeStringToVirtual(
            "assets/minecraft/models/block", modelName + ".json", model.toString());

        if (Settings.DEBUG.toBool()) {
            Logs.logInfo("Generated base block model for " + modelName);
        }
    }

    private String normalizeTexturePath(String texture) {
        if (!texture.contains("/")) {
            return "default/" + texture;
        }
        return texture;
    }

    private String generateBlockstateForMaterial(Material material, String modelName) {
        ShapedBlockType type = ShapedBlockType.fromMaterial(material);
        if (type == null) return "{}";

        String simpleModelName = modelName;
        if (modelName.contains("/")) {
            simpleModelName = modelName.substring(modelName.lastIndexOf("/") + 1);
        }
        String blockModelName = "block/" + simpleModelName;

        return switch (type) {
            case STAIR -> generateStairsBlockstate(blockModelName);
            case SLAB -> generateSlabBlockstate(blockModelName);
            case DOOR -> generateDoorBlockstate(blockModelName);
            case TRAPDOOR -> generateTrapdoorBlockstate(blockModelName);
            case GRATE -> generateGrateBlockstate(blockModelName);
            case BULB -> generateBulbBlockstate(blockModelName);
            case FENCE -> generateFenceBlockstate(blockModelName);
            case FENCE_GATE -> generateFenceGateBlockstate(blockModelName);
            case WALL -> generateWallBlockstate(blockModelName);
            case BUTTON -> generateButtonBlockstate(blockModelName);
            case PRESSURE_PLATE -> generatePressurePlateBlockstate(blockModelName);
        };
    }
    // --- Blockstate Generation Methods ---

    private String generateStairsBlockstate(String modelName) {
        // ... (Same as original ShapedBlockMechanic)
        // Simplified example for brevity
        JsonObject blockstate = new JsonObject();
        JsonObject variants = new JsonObject();
        String[] facings = {"east", "north", "south", "west"};
        String[] halves = {"bottom", "top"};
        String[] shapes = {"straight", "inner_left", "inner_right", "outer_left", "outer_right"};

        for (String facing : facings) {
            for (String half : halves) {
                for (String shape : shapes) {
                    String key = "facing=" + facing + ",half=" + half + ",shape=" + shape + ",waterlogged=false";
                    JsonObject model = new JsonObject();
                    model.addProperty("model", modelName + (shape.equals("straight") ? "" : "_" + shape));
                    int yRot = switch(facing) {
                        case "south" -> 90; case "west" -> 180; case "north" -> 270; default -> 0;
                    };
                    if (half.equals("top")) model.addProperty("x", 180);
                    if (yRot != 0) model.addProperty("y", yRot);
                    model.addProperty("uvlock", true);
                    variants.add(key, model);
                }
            }
        }
        blockstate.add("variants", variants);
        return blockstate.toString();
    }

    private String generateSlabBlockstate(String modelName) {
        JsonObject blockstate = new JsonObject();
        JsonObject variants = new JsonObject();
        variants.addProperty("type=bottom,waterlogged=false", new JsonObject().addProperty("model", modelName));
        variants.addProperty("type=top,waterlogged=false", new JsonObject().addProperty("model", modelName + "_top"));
        variants.addProperty("type=double,waterlogged=false", new JsonObject().addProperty("model", modelName + "_double"));
        blockstate.add("variants", variants);
        return blockstate.toString();
    }

    private String generateDoorBlockstate(String modelName) {
        // ... (Similar to original)
        JsonObject blockstate = new JsonObject();
        JsonObject variants = new JsonObject();
        String[] facings = {"east", "north", "south", "west"};
        String[] halves = {"lower", "upper"};
        String[] hinges = {"left", "right"};
        String[] opens = {"false", "true"};
        for (String facing : facings) {
            for (String half : halves) {
                for (String hinge : hinges) {
                    for (String open : opens) {
                        String key = "facing=" + facing + ",half=" + half + ",hinge=" + hinge + ",open=" + open + ",powered=false";
                        JsonObject model = new JsonObject();
                        model.addProperty("model", modelName + "_" + half + (hinge.equals("right") ? "_hinge" : "") + (open.equals("true") ? "_open" : ""));
                        int yRot = switch(facing) {
                            case "south" -> 90; case "west" -> 180; case "north" -> 270; default -> 0;
                        };
                        if (open.equals("true")) {
                            yRot = (yRot + (hinge.equals("left") ? 90 : -90)) % 360;
                        }
                        if (yRot != 0) model.addProperty("y", yRot);
                        variants.add(key, model);
                    }
                }
            }
        }
        blockstate.add("variants", variants);
        return blockstate.toString();
    }

    private String generateTrapdoorBlockstate(String modelName) {
        // ... (Similar to original)
        JsonObject blockstate = new JsonObject();
        JsonObject variants = new JsonObject();
        String[] facings = {"east", "north", "south", "west"};
        String[] halves = {"bottom", "top"};
        String[] opens = {"false", "true"};

        for (String facing : facings) {
            for (String half : halves) {
                for (String open : opens) {
                    String key = "facing=" + facing + ",half=" + half + ",open=" + open + ",powered=false,waterlogged=false";
                    JsonObject model = new JsonObject();
                    model.addProperty("model", modelName + (half.equals("top") && !open.equals("true") ? "_top" : (open.equals("true") ? "_open" : "_bottom")));
                    int yRot = switch(facing) {
                        case "south" -> 90; case "west" -> 180; case "north" -> 270; default -> 0;
                    };
                    if (yRot != 0) model.addProperty("y", yRot);
                    variants.add(key, model);
                }
            }
        }
        blockstate.add("variants", variants);
        return blockstate.toString();
    }

    private String generateGrateBlockstate(String modelName) {        JsonObject blockstate = new JsonObject();
        JsonObject variants = new JsonObject();
        variants.addProperty("waterlogged=false", new JsonObject().addProperty("model", modelName));
        blockstate.add("variants", variants);
        return blockstate.toString();
    }

    private String generateBulbBlockstate(String modelName) {
        JsonObject blockstate = new JsonObject();
        JsonObject variants = new JsonObject();
        variants.addProperty("lit=false,powered=false", new JsonObject().addProperty("model", modelName));
        variants.addProperty("lit=true,powered=true", new JsonObject().addProperty("model", modelName)); // Same model
        blockstate.add("variants", variants);
        return blockstate.toString();
    }

    private String generateFenceBlockstate(String modelName) {
        JsonObject blockstate = new JsonObject();
        JsonArray multipart = new JsonArray();
        // Post part
        JsonObject postPart = new JsonObject();
        postPart.addProperty("apply", new JsonObject().addProperty("model", modelName));
        multipart.add(postPart);
        // Side parts
        for(String dir : new String[]{"north", "east", "south", "west"}) {
             JsonObject sidePart = new JsonObject();
             sidePart.addProperty("when", new JsonObject().addProperty(dir, "true"));
             sidePart.addProperty("apply", new JsonObject().addProperty("model", modelName + "_side").addProperty("y", switch(dir) {case "north": return 0; case "east": return 90; case "south": return 180; case "west": return 270; default: return 0;}));
             multipart.add(sidePart);
        }
        blockstate.add("multipart", multipart);
        return blockstate.toString();
    }

    private String generateFenceGateBlockstate(String modelName) {
        JsonObject blockstate = new JsonObject();
        JsonObject variants = new JsonObject();
        String[] facings = {"north", "south", "east", "west"};
        String[] opens = {"true", "false"};
        String[] inWalls = {"true", "false"};

        for (String facing : facings) {
            for (String open : opens) {
                for (String inWall : inWalls) {
                    String key = "facing=" + facing + ",open=" + open + ",in_wall=" + inWall + ",powered=false";
                    JsonObject model = new JsonObject();
                    String suffix = open.equals("true") ? "_open" : (inWall.equals("true") ? "_wall" : "_standing");
                    model.addProperty("model", modelName + suffix);
                    int yRot = switch(facing) {
                        case "south" -> 90; case "west" -> 180; case "north" -> 270; default -> 0;                    };
                    if (yRot != 0) model.addProperty("y", yRot);
                    variants.add(key, model);
                }
            }
        }
        blockstate.add("variants", variants);
        return blockstate.toString();
    }

    private String generateWallBlockstate(String modelName) {
        JsonObject blockstate = new JsonObject();
        JsonArray multipart = new JsonArray();
        // Post part
        JsonObject postPart = new JsonObject();
        postPart.addProperty("when", new JsonObject().addProperty("up", "true"));
        postPart.addProperty("apply", new JsonObject().addProperty("model", modelName + "_post"));
        multipart.add(postPart);
        // Default post if no up connection
        JsonObject defaultPostPart = new JsonObject();
        defaultPostPart.addProperty("when", new JsonObject().add("up", "false"));
        defaultPostPart.addProperty("apply", new JsonObject().addProperty("model", modelName + "_post"));
        multipart.add(defaultPostPart);
        // Side parts
        for(String dir : new String[]{"north", "east", "south", "west"}) {
             JsonObject sidePart = new JsonObject();
             sidePart.addProperty("when", new JsonObject().addProperty(dir, "true"));
             sidePart.addProperty("apply", new JsonObject().addProperty("model", modelName + "_side").addProperty("y", switch(dir) {case "north": return 0; case "east": return 90; case "south": return 180; case "west": return 270; default: return 0;}));
             multipart.add(sidePart);
        }
        blockstate.add("multipart", multipart);
        return blockstate.toString();
    }

    private String generateButtonBlockstate(String modelName) {
        JsonObject blockstate = new JsonObject();
        JsonObject variants = new JsonObject();
        // Example for all 32 variants (simplified)
        JsonObject floorUpFalse = new JsonObject(); floorUpFalse.addProperty("model", modelName); variants.add("face=floor,facing=north,powered=false", floorUpFalse);
        JsonObject floorUpTrue = new JsonObject(); floorUpTrue.addProperty("model", modelName + "_pressed"); variants.add("face=floor,facing=north,powered=true", floorUpTrue);
        JsonObject floorDownFalse = new JsonObject(); floorDownFalse.addProperty("model", modelName); floorDownFalse.addProperty("x", 180); variants.add("face=ceiling,facing=north,powered=false", floorDownFalse);
        JsonObject floorDownTrue = new JsonObject(); floorDownTrue.addProperty("model", modelName + "_pressed"); floorDownTrue.addProperty("x", 180); variants.add("face=ceiling,facing=north,powered=true", floorDownTrue);
        JsonObject wallNorthFalse = new JsonObject(); wallNorthFalse.addProperty("model", modelName); wallNorthFalse.addProperty("x", 90); variants.add("face=wall,facing=north,powered=false", wallNorthFalse);
        JsonObject wallNorthTrue = new JsonObject(); wallNorthTrue.addProperty("model", modelName + "_pressed"); wallNorthTrue.addProperty("x", 90); variants.add("face=wall,facing=north,powered=true", wallNorthTrue);
        JsonObject wallSouthFalse = new JsonObject(); wallSouthFalse.addProperty("model", modelName); wallSouthFalse.addProperty("x", 90); wallSouthFalse.addProperty("y", 180); variants.add("face=wall,facing=south,powered=false", wallSouthFalse);
        JsonObject wallSouthTrue = new JsonObject(); wallSouthTrue.addProperty("model", modelName + "_pressed"); wallSouthTrue.addProperty("x", 90); wallSouthTrue.addProperty("y", 180); variants.add("face=wall,facing=south,powered=true", wallSouthTrue);
        JsonObject wallEastFalse = new JsonObject(); wallEastFalse.addProperty("model", modelName); wallEastFalse.addProperty("x", 90); wallEastFalse.addProperty("y", 90); variants.add("face=wall,facing=east,powered=false", wallEastFalse);
        JsonObject wallEastTrue = new JsonObject(); wallEastTrue.addProperty("model", modelName + "_pressed"); wallEastTrue.addProperty("x", 90); wallEastTrue.addProperty("y", 90); variants.add("face=wall,facing=east,powered=true", wallEastTrue);
        JsonObject wallWestFalse = new JsonObject(); wallWestFalse.addProperty("model", modelName); wallWestFalse.addProperty("x", 90); wallWestFalse.addProperty("y", 270); variants.add("face=wall,facing=west,powered=false", wallWestFalse);
        JsonObject wallWestTrue = new JsonObject(); wallWestTrue.addProperty("model", modelName + "_pressed"); wallWestTrue.addProperty("x", 90); wallWestTrue.addProperty("y", 270); variants.add("face=wall,facing=west,powered=true", wallWestTrue);
        blockstate.add("variants", variants);
        return blockstate.toString();
    }

    private String generatePressurePlateBlockstate(String modelName) {
        JsonObject blockstate = new JsonObject();
        JsonObject variants = new JsonObject();
        variants.addProperty("powered=false", new JsonObject().addProperty("model", modelName));
        variants.addProperty("powered=true", new JsonObject().addProperty("model", modelName + "_down"));
        blockstate.add("variants", variants);
        return blockstate.toString();
    }

    public ShapedBlockMechanic getMechanicByMaterial(Material material) {
        return mechanicBy    }

    public boolean isCustomShapedBlock(Material material) {
        return mechanicByMaterial.containsKey(material);
    }

    public List<String> getToolTypes() {
        return toolTypes;
    }

    public Map<Material, ShapedBlockMechanic> getAllMechanics() {
        return new HashMap<>(mechanicByMaterial);
    }

    public boolean hasCustomBlocks() {
        return !mechanicByMaterial.isEmpty();
    }
}
