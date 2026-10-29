package net.okitsu.ysmepicfightcompat.diagnostics;

import net.okitsu.ysmepicfightcompat.mesh.AuxiliaryBoneLayout;
import org.joml.Matrix4f;
import yesman.epicfight.api.utils.math.OpenMatrix4f;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Detached, bounded render data. No game objects or live scratch arrays reach the writer. */
public final class IssuePoseSnapshot {
    private IssuePoseSnapshot() {}

    public static Map<String, Object> matrices(OpenMatrix4f[] source, int maximum) {
        maximum = Math.max(0, maximum);
        int count = source == null ? 0 : Math.min(source.length, maximum);
        var values = new ArrayList<Float[]>(count);
        int unavailable = 0;
        for (int index = 0; index < count; index++) {
            Float[] matrix = source[index] == null ? new Float[16] : matrix(source[index]);
            for (Float value : matrix) if (value == null) unavailable++;
            values.add(matrix);
        }
        var result = new LinkedHashMap<String, Object>();
        result.put("layout", "column_major_4x4");
        result.put("count", count);
        result.put("truncated", source != null && source.length > maximum);
        result.put("unavailable_components", unavailable);
        result.put("values", values);
        return result;
    }

    public static Float[] matrix(OpenMatrix4f value) {
        float[] components = {value.m00, value.m01, value.m02, value.m03,
                value.m10, value.m11, value.m12, value.m13,
                value.m20, value.m21, value.m22, value.m23,
                value.m30, value.m31, value.m32, value.m33};
        Float[] result = new Float[16];
        for (int index = 0; index < components.length; index++)
            result[index] = Float.isFinite(components[index]) ? components[index] : null;
        return result;
    }

    public static Float[] matrix(Matrix4f value) {
        float[] components = value.get(new float[16]);
        Float[] result = new Float[16];
        for (int index = 0; index < components.length; index++)
            result[index] = Float.isFinite(components[index]) ? components[index] : null;
        return result;
    }

    public static List<Map<String, Object>> bones(AuxiliaryBoneLayout layout) {
        var result = new ArrayList<Map<String, Object>>(layout.entries().size());
        for (var entry : layout.entries()) {
            var bone = new LinkedHashMap<String, Object>();
            bone.put("name", entry.bone().name());
            bone.put("pose", entry.poseIndex());
            bone.put("parent", entry.parentAuxiliaryIndex());
            bone.put("anchor", entry.anchorJoint());
            bone.put("pivot_unscaled_blocks", new float[]{entry.bone().pivotX(),
                    entry.bone().pivotY(), entry.bone().pivotZ()});
            bone.put("bind_world_unscaled", matrix(entry.bindWorld()));
            result.add(bone);
        }
        return result;
    }
}
