package net.okitsu.ysmepicfightcompat.diagnostics;

import com.google.gson.GsonBuilder;
import org.junit.jupiter.api.Test;
import yesman.epicfight.api.utils.math.OpenMatrix4f;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class IssuePoseSnapshotTest {
    @Test
    void detachesReusableMatricesAndPreservesMissingComponentsAsNull() {
        var live = new OpenMatrix4f();
        live.m30 = 2;
        live.m31 = Float.NaN;
        var snapshot = IssuePoseSnapshot.matrices(new OpenMatrix4f[]{live, null}, 2);
        live.m30 = 99;
        @SuppressWarnings("unchecked")
        var values = (List<Float[]>) snapshot.get("values");
        assertEquals(2f, values.get(0)[12]);
        assertNull(values.get(0)[13]);
        assertEquals(17, snapshot.get("unavailable_components"));
        assertDoesNotThrow(() -> new GsonBuilder().serializeNulls().create().toJson(snapshot));
        assertEquals(1, IssuePoseSnapshot.matrices(new OpenMatrix4f[]{live, live}, 1).get("count"));
        assertEquals(true, IssuePoseSnapshot.matrices(new OpenMatrix4f[]{live, live}, 1).get("truncated"));
    }
}
