package net.okitsu.ysmepicfightcompat.diagnostics;

import com.google.gson.JsonObject;
import java.math.BigDecimal;
import java.nio.FloatBuffer;
import yesman.epicfight.api.utils.math.OpenMatrix4f;

/** Bounded interchange for issue snapshots. Missing components never become fake zeroes. */
public final class RecordedPoseData {
    private RecordedPoseData() {}

    public static OpenMatrix4f[] readMatrices(JsonObject object, int minimum, int maximum) {
        if (object == null || minimum < 0 || maximum < minimum)
            throw new IllegalArgumentException("记录矩阵范围无效");
        var layout = object.get("layout");
        var truncated = object.get("truncated");
        if (layout == null || !layout.isJsonPrimitive()
                || !layout.getAsJsonPrimitive().isString()
                || !"column_major_4x4".equals(layout.getAsString())
                || truncated == null || !truncated.isJsonPrimitive()
                || !truncated.getAsJsonPrimitive().isBoolean()
                || truncated.getAsBoolean())
            throw new IllegalArgumentException("记录矩阵不完整或排列不支持");
        var valuesValue = object.get("values");
        var countValue = object.get("count");
        if (valuesValue == null || !valuesValue.isJsonArray()
                || countValue == null || !countValue.isJsonPrimitive()
                || !countValue.getAsJsonPrimitive().isNumber())
            throw new IllegalArgumentException("记录矩阵数量无效");
        var values = valuesValue.getAsJsonArray();
        int count;
        try {
            BigDecimal number = countValue.getAsBigDecimal();
            count = number.intValueExact();
        } catch (NumberFormatException | ArithmeticException exception) {
            throw new IllegalArgumentException("记录矩阵数量无效", exception);
        }
        if (count != values.size() || count < minimum || count > maximum)
            throw new IllegalArgumentException("记录矩阵数量无效");
        var result = new OpenMatrix4f[count];
        for (int index = 0; index < count; index++) {
            var rowValue = values.get(index);
            if (rowValue == null || !rowValue.isJsonArray())
                throw new IllegalArgumentException("记录矩阵行无效");
            var row = rowValue.getAsJsonArray();
            if (row.size() != 16) throw new IllegalArgumentException("记录矩阵长度无效");
            var components = FloatBuffer.allocate(16);
            for (var componentValue : row) {
                if (!componentValue.isJsonPrimitive() || !componentValue.getAsJsonPrimitive().isNumber())
                    throw new IllegalArgumentException("记录中存在缺失的矩阵分量");
                double number = componentValue.getAsDouble();
                float component = (float) number;
                if (!Double.isFinite(number) || !Float.isFinite(component))
                    throw new IllegalArgumentException("记录矩阵不是有限数值");
                components.put(component);
            }
            components.flip();
            result[index] = new OpenMatrix4f().load(components);
        }
        return result;
    }
}
