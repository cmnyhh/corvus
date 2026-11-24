package dev.dronepal.client;

import com.google.gson.*;
import java.io.*;
import java.nio.file.*;
import java.util.List;

public final class DroneMeshCheck {
    public static void main(String[] args) throws Exception {
        String fixture = """
          {"minecraft:geometry":[{"description":{"texture_width":32,"texture_height":16},"bones":[
          {"name":"parent","pivot":[0,0,0],"rotation":[0,90,0]},
          {"name":"child","parent":"parent","cubes":[{"origin":[2,0,0],"size":[1,2,3],"pivot":[2,0,0],"rotation":[0,0,90],
          "uv":{"north":{"uv":[8,4],"uv_size":[-2,6]}}}]}]}]}
          """;
        List<DroneMesh.Vertex> v = DroneMesh.bake(new StringReader(fixture));
        if (v.size() != 4) throw new AssertionError("absent faces");
        near(v.get(0).x(),0); near(v.get(0).y(),1F/16); near(v.get(0).z(),0);
        near(v.get(0).nx(),-1); near(v.get(0).u(),8F/32); near(v.get(0).v(),4F/16);
        near(v.get(2).u(),6F/32); near(v.get(2).v(),10F/16);
        Path file = Path.of("src/main/resources/assets/huhu_dronepal/geo/drone.geo.json");
        String json = Files.readString(file);
        long faces = 0;
        for (JsonElement bone : JsonParser.parseString(json).getAsJsonObject().getAsJsonArray("minecraft:geometry").get(0).getAsJsonObject().getAsJsonArray("bones")) {
            if (bone.getAsJsonObject().has("cubes")) for (JsonElement cube : bone.getAsJsonObject().getAsJsonArray("cubes")) faces += cube.getAsJsonObject().getAsJsonObject("uv").size();
        }
        var mesh = DroneMesh.bake(new StringReader(json));
        if (faces == 0 || mesh.size() != 4 * faces) throw new AssertionError("missing authored faces");
        try (var source = Files.newBufferedReader(Path.of("src/main/resources/assets/huhu_dronepal/animations/drone.animation.json"))) {
            var damage = DroneClip.load(source).get("damaged");
            if (!damage.bone().equals("root")) throw new AssertionError("damage bone");
            near(damage.hurt(0,10,0).length(),0); near(damage.hurt(10,10,0).length(),0);
            near(damage.hurt(10,10,5F/6).z,7); near(damage.hurt(1,10,1).length(),0);
        }
        System.out.printf("Mesh check passed: %d authored faces, %d rendered vertices%n", faces, mesh.size());
    }
    private static void near(float a, float b) { if (!(Math.abs(a-b)<0.00001f)) throw new AssertionError(a+" != "+b); }
}
