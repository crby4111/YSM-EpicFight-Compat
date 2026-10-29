package net.okitsu.ysmepicfightcompat.diagnostics;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import org.junit.jupiter.api.Test;

class RecordedPoseDataTest {
    private static JsonObject snapshot() {
        JsonArray row = new JsonArray();
        for (int index = 0; index < 16; index++) row.add(index % 5 == 0 ? 1 : 0);
        JsonArray values = new JsonArray();
        values.add(row);
        JsonObject object = new JsonObject();
        object.addProperty("layout", "column_major_4x4");
        object.addProperty("count", 1);
        object.addProperty("truncated", false);
        object.add("values", values);
        return object;
    }

    @Test
    void acceptsOneCompleteColumnMajorMatrixWithinBounds() {
        var matrices = RecordedPoseData.readMatrices(snapshot(), 1, 2);
        assertEquals(1, matrices.length);
        assertEquals(1, matrices[0].m00);
        assertEquals(1, matrices[0].m33);
    }

    @Test
    void rejectsMissingOrUnsupportedLayoutAndTruncatedSnapshots() {
        var missing = snapshot();
        missing.remove("layout");
        assertThrows(IllegalArgumentException.class,
                () -> RecordedPoseData.readMatrices(missing, 1, 1));

        var truncated = snapshot();
        truncated.addProperty("truncated", true);
        assertThrows(IllegalArgumentException.class,
                () -> RecordedPoseData.readMatrices(truncated, 1, 1));
    }

    @Test
    void rejectsNonIntegralAndOutOfRangeCounts() {
        var fractional = snapshot();
        fractional.add("count", new JsonPrimitive(1.5));
        assertThrows(IllegalArgumentException.class,
                () -> RecordedPoseData.readMatrices(fractional, 1, 1));

        assertThrows(IllegalArgumentException.class,
                () -> RecordedPoseData.readMatrices(snapshot(), 2, 4));
        assertThrows(IllegalArgumentException.class,
                () -> RecordedPoseData.readMatrices(snapshot(), 0, 0));
    }

    @Test
    void rejectsMissingAndNonFiniteMatrixComponents() {
        var missing = snapshot();
        missing.getAsJsonArray("values").get(0).getAsJsonArray().set(4,
                com.google.gson.JsonNull.INSTANCE);
        assertThrows(IllegalArgumentException.class,
                () -> RecordedPoseData.readMatrices(missing, 1, 1));

        var nonFinite = snapshot();
        nonFinite.getAsJsonArray("values").get(0).getAsJsonArray().set(4,
                new JsonPrimitive(Double.NaN));
        assertThrows(IllegalArgumentException.class,
                () -> RecordedPoseData.readMatrices(nonFinite, 1, 1));

        var floatOverflow = snapshot();
        floatOverflow.getAsJsonArray("values").get(0).getAsJsonArray().set(4,
                new JsonPrimitive(1e100));
        assertThrows(IllegalArgumentException.class,
                () -> RecordedPoseData.readMatrices(floatOverflow, 1, 1));
    }

    @Test
    void rejectsMissingObjectsAndInvalidRangesAsInputErrors() {
        assertThrows(IllegalArgumentException.class,
                () -> RecordedPoseData.readMatrices(null, 0, 1));
        assertThrows(IllegalArgumentException.class,
                () -> RecordedPoseData.readMatrices(snapshot(), 2, 1));
    }
}
