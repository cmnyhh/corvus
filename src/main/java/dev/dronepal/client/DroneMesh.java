package dev.dronepal.client;

import com.google.gson.*;
import java.io.Reader;
import java.util.*;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/** Bakes the authored Y-up, -Z-forward Bedrock mesh in its absolute bind coordinates. */
final class DroneMesh {
    record Vertex(float x, float y, float z, float u, float v, float nx, float ny, float nz) {}
    private static final String[] FACES = {"north", "south", "east", "west", "up", "down"};
    // Corner bits: high X=1, high Y=2, high Z=4. UV order: TL, BL, BR, TR.
    private static final int[][] CORNERS = {{3,1,0,2},{6,4,5,7},{7,5,1,3},{2,0,4,6},{2,6,7,3},{4,0,1,5}};
    private static final int[][] NORMAL = {{0,0,-1},{0,0,1},{1,0,0},{-1,0,0},{0,1,0},{0,-1,0}};
    record Bone(String name, Vector3f pivot, Vector3f rotation, List<Vertex> vertices, List<Bone> children) {}
    static List<Vertex> bake(Reader reader) {
        List<Vertex> result = new ArrayList<>();
        for (Bone root : rig(reader)) flatten(root, new Matrix4f(), result);
        return List.copyOf(result);
    }
    private static void flatten(Bone bone, Matrix4f parent, List<Vertex> result) {
        Vector3f p = bone.pivot(), r = bone.rotation();
        Matrix4f m = new Matrix4f(parent).translate(p).rotateZYX(r.z,r.y,r.x).translate(-p.x,-p.y,-p.z);
        for (Vertex v : bone.vertices()) {
            Vector3f pos = m.transformPosition(new Vector3f(v.x,v.y,v.z));
            Vector3f n = m.transformDirection(new Vector3f(v.nx,v.ny,v.nz));
            result.add(new Vertex(pos.x,pos.y,pos.z,v.u,v.v,n.x,n.y,n.z));
        }
        for (Bone child : bone.children()) flatten(child,m,result);
    }
    static List<Bone> rig(Reader reader) {
        JsonObject geo = JsonParser.parseReader(reader).getAsJsonObject().getAsJsonArray("minecraft:geometry").get(0).getAsJsonObject();
        JsonObject desc = geo.getAsJsonObject("description");
        float w = desc.get("texture_width").getAsFloat(), h = desc.get("texture_height").getAsFloat();
        Map<String,JsonObject> bones = new LinkedHashMap<>();
        for (JsonElement element : geo.getAsJsonArray("bones")) {
            JsonObject b = element.getAsJsonObject(); bones.put(b.get("name").getAsString(), b);
        }
        Map<String,List<Bone>> children = new HashMap<>();
        for (String name : bones.keySet()) children.put(name,new ArrayList<>());
        List<Bone> roots = new ArrayList<>();
        int count = 0;
        for (JsonObject b : bones.values()) {
            String name = b.get("name").getAsString();
            boneTransform(b,bones,new HashSet<>()); // Validate parent links, including empty locator bones.
            List<Vertex> out = new ArrayList<>();
            Bone bone = new Bone(name,vec(b,"pivot").div(16F),vec(b,"rotation").mul((float)Math.PI/180F),out,children.get(name));
            (b.has("parent") ? children.get(b.get("parent").getAsString()) : roots).add(bone);
            if (!b.has("cubes")) continue;
            for (JsonElement elem : b.getAsJsonArray("cubes")) {
                JsonObject cube = elem.getAsJsonObject();
                Matrix4f matrix = rotate(new Matrix4f(), cube);
                JsonArray origin = cube.getAsJsonArray("origin"), size = cube.getAsJsonArray("size");
                JsonObject uv = cube.getAsJsonObject("uv");
                for (int f=0; f<FACES.length; f++) {
                    if (!uv.has(FACES[f])) continue;
                    JsonObject face = uv.getAsJsonObject(FACES[f]);
                    JsonArray start = face.getAsJsonArray("uv"), span = face.getAsJsonArray("uv_size");
                    Vector3f n = matrix.transformDirection(new Vector3f(NORMAL[f][0],NORMAL[f][1],NORMAL[f][2])).normalize();
                    for (int j=0; j<4; j++) {
                        int c = CORNERS[f][j];
                        Vector3f pos = matrix.transformPosition(new Vector3f(coord(origin,size,c,0),coord(origin,size,c,1),coord(origin,size,c,2))).div(16F);
                        float u = at(start,0) + (j>=2 ? at(span,0) : 0), v = at(start,1) + (j==1 || j==2 ? at(span,1) : 0);
                        out.add(new Vertex(pos.x,pos.y,pos.z,u/w,v/h,n.x,n.y,n.z));
                    }
                }
            }
            count += out.size();
        }
        if (count == 0) throw new IllegalArgumentException("Empty drone mesh");
        return List.copyOf(roots);
    }
    private static Vector3f vec(JsonObject b,String name) {
        JsonArray a=b.getAsJsonArray(name);
        return new Vector3f(at(a,0),at(a,1),at(a,2));
    }
    private static Matrix4f boneTransform(JsonObject bone, Map<String,JsonObject> bones, Set<String> visited) {
        String name = bone.get("name").getAsString();
        if (!visited.add(name)) throw new IllegalArgumentException("Cyclic bone: " + name);
        Matrix4f parent = bone.has("parent") ? boneTransform(Objects.requireNonNull(bones.get(bone.get("parent").getAsString())),bones,visited) : new Matrix4f();
        return rotate(parent,bone);
    }
    private static Matrix4f rotate(Matrix4f matrix, JsonObject object) {
        if (!object.has("rotation")) return matrix;
        JsonArray r = object.getAsJsonArray("rotation"), p = object.getAsJsonArray("pivot");
        float x=at(p,0), y=at(p,1), z=at(p,2), radians=(float)Math.PI/180;
        return matrix.translate(x,y,z).rotateZYX(at(r,2)*radians,at(r,1)*radians,at(r,0)*radians).translate(-x,-y,-z);
    }
    private static float coord(JsonArray origin, JsonArray size, int corner, int axis) {
        return at(origin,axis) + ((corner & (1 << axis)) == 0 ? 0 : at(size,axis));
    }
    private static float at(JsonArray arr,int i) { return arr == null ? 0 : arr.get(i).getAsFloat(); }
}
