package dev.corvus.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.Reader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Reads the authored Bedrock geometry ({@code assets/huhu_dronepal/geo/*.geo.json}) into a bone tree
 * that the 1.20.1 {@link DroneRenderer} walks.
 *
 * <p>Vertices stay in the model's absolute bind coordinates (Y up, -Z forward, 1 unit = 1/16 block);
 * each cube's own {@code rotation}/{@code pivot} is baked into its vertices, while bone rotations are
 * applied at draw time so the tree reads exactly like the authoring file. This is the same layout the
 * 1.21 implementation uses, so both versions point at the same geo files.</p>
 */
final class DroneMesh {
    /** Position in blocks, UV normalised to 0..1, normal in model space. */
    record Vertex(float x, float y, float z, float u, float v, float nx, float ny, float nz) {}

    record Bone(String name, Vector3f pivot, Vector3f rotation, List<Vertex> vertices, List<Bone> children) {}

    private static final String[] FACES = {"north", "south", "east", "west", "up", "down"};
    /** Corner bits: high X = 1, high Y = 2, high Z = 4. UV order per face: TL, BL, BR, TR. */
    private static final int[][] CORNERS = {{3, 1, 0, 2}, {6, 4, 5, 7}, {7, 5, 1, 3}, {2, 0, 4, 6}, {2, 6, 7, 3}, {4, 0, 1, 5}};
    private static final int[][] NORMAL = {{0, 0, -1}, {0, 0, 1}, {1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}};

    private DroneMesh() {}

    static List<Bone> rig(Reader reader) {
        JsonObject geo = JsonParser.parseReader(reader).getAsJsonObject()
                .getAsJsonArray("minecraft:geometry").get(0).getAsJsonObject();
        JsonObject description = geo.getAsJsonObject("description");
        float width = description.get("texture_width").getAsFloat();
        float height = description.get("texture_height").getAsFloat();

        Map<String, JsonObject> bones = new LinkedHashMap<>();
        for (JsonElement element : geo.getAsJsonArray("bones")) {
            JsonObject bone = element.getAsJsonObject();
            bones.put(bone.get("name").getAsString(), bone);
        }
        Map<String, List<Bone>> children = new HashMap<>();
        for (String name : bones.keySet()) children.put(name, new ArrayList<>());
        List<Bone> roots = new ArrayList<>();
        int vertexCount = 0;
        for (JsonObject json : bones.values()) {
            String name = json.get("name").getAsString();
            boneTransform(json, bones, new HashSet<>()); // validates parent links, including empty locators
            List<Vertex> vertices = new ArrayList<>();
            Bone bone = new Bone(name, vec(json, "pivot").div(16F),
                    vec(json, "rotation").mul((float) Math.PI / 180F), vertices, children.get(name));
            (json.has("parent") ? children.get(json.get("parent").getAsString()) : roots).add(bone);
            if (!json.has("cubes")) continue;
            for (JsonElement element : json.getAsJsonArray("cubes")) {
                JsonObject cube = element.getAsJsonObject();
                Matrix4f matrix = rotate(new Matrix4f(), cube);
                JsonArray origin = cube.getAsJsonArray("origin");
                JsonArray size = cube.getAsJsonArray("size");
                JsonObject uv = cube.getAsJsonObject("uv");
                for (int face = 0; face < FACES.length; face++) {
                    if (!uv.has(FACES[face])) continue;
                    JsonObject faceUv = uv.getAsJsonObject(FACES[face]);
                    JsonArray start = faceUv.getAsJsonArray("uv");
                    JsonArray span = faceUv.getAsJsonArray("uv_size");
                    Vector3f normal = matrix.transformDirection(
                            new Vector3f(NORMAL[face][0], NORMAL[face][1], NORMAL[face][2])).normalize();
                    for (int corner = 0; corner < 4; corner++) {
                        int bits = CORNERS[face][corner];
                        Vector3f pos = matrix.transformPosition(new Vector3f(
                                coordinate(origin, size, bits, 0),
                                coordinate(origin, size, bits, 1),
                                coordinate(origin, size, bits, 2))).div(16F);
                        float u = at(start, 0) + (corner >= 2 ? at(span, 0) : 0F);
                        float v = at(start, 1) + (corner == 1 || corner == 2 ? at(span, 1) : 0F);
                        vertices.add(new Vertex(pos.x, pos.y, pos.z, u / width, v / height,
                                normal.x, normal.y, normal.z));
                    }
                }
            }
            vertexCount += vertices.size();
        }
        if (vertexCount == 0) throw new IllegalArgumentException("Empty drone mesh");
        return List.copyOf(roots);
    }

    private static Vector3f vec(JsonObject bone, String name) {
        JsonArray array = bone.getAsJsonArray(name);
        return new Vector3f(at(array, 0), at(array, 1), at(array, 2));
    }

    private static Matrix4f boneTransform(JsonObject bone, Map<String, JsonObject> bones, Set<String> visited) {
        String name = bone.get("name").getAsString();
        if (!visited.add(name)) throw new IllegalArgumentException("Cyclic bone: " + name);
        Matrix4f parent = bone.has("parent")
                ? boneTransform(Objects.requireNonNull(bones.get(bone.get("parent").getAsString())), bones, visited)
                : new Matrix4f();
        return rotate(parent, bone);
    }

    private static Matrix4f rotate(Matrix4f matrix, JsonObject object) {
        if (!object.has("rotation")) return matrix;
        JsonArray rotation = object.getAsJsonArray("rotation");
        JsonArray pivot = object.getAsJsonArray("pivot");
        float x = at(pivot, 0);
        float y = at(pivot, 1);
        float z = at(pivot, 2);
        float radians = (float) Math.PI / 180F;
        return matrix.translate(x, y, z)
                .rotateZYX(at(rotation, 2) * radians, at(rotation, 1) * radians, at(rotation, 0) * radians)
                .translate(-x, -y, -z);
    }

    private static float coordinate(JsonArray origin, JsonArray size, int corner, int axis) {
        return at(origin, axis) + ((corner & (1 << axis)) == 0 ? 0F : at(size, axis));
    }

    private static float at(JsonArray array, int index) {
        return array == null ? 0F : array.get(index).getAsFloat();
    }
}
