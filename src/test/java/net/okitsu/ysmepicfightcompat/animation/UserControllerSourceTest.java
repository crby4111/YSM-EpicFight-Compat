package net.okitsu.ysmepicfightcompat.animation;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;

import net.okitsu.ysmepicfightcompat.geometry.GeometryDocument;
import net.okitsu.ysmepicfightcompat.mesh.AuxiliaryBoneLayout;

import org.junit.jupiter.api.Test;

import java.util.*;

class UserControllerSourceTest {
    private static final class Env implements ClipTimeEnvironment, MolangScriptRuntime.Host {
        final Map<Integer, Double> values = new HashMap<>();
        double time;
        int effects;
        MolangScriptRuntime scripts;

        public double readVariable(int slot) {
            return values.getOrDefault(slot, 0d);
        }

        public boolean hasVariable(int slot) {
            return values.containsKey(slot);
        }

        public void writeVariable(int slot, double value) {
            values.put(slot, value);
        }

        public double readQuery(int slot) {
            return ExpressionEngine.slotName(slot).equals("query.anim_time") ? time : 0;
        }

        public double invoke(String name, double[] args) {
            return 0;
        }

        public double invokeWithText(String name, String[] args) {
            effects++;
            return 0;
        }

        public void clipTime(double time) {
            this.time = time;
        }

        @Override
        public MolangScriptRuntime scripts() {
            return scripts;
        }

        void value(String name, double value) {
            writeVariable(ExpressionEngine.slot(name), value);
        }

        double value(String name) {
            return readVariable(ExpressionEngine.slot(name));
        }
    }

    private static MotionRuleSet rules(String json) {
        return MotionRuleSet.parse(JsonParser.parseString(json).getAsJsonObject());
    }

    private static final MotionRuleSet.Facts ON =
            new MotionRuleSet.Facts(Map.of("attacking", "true"), Set.of());
    private static final MotionRuleSet.Facts OFF =
            new MotionRuleSet.Facts(Map.of("attacking", "false"), Set.of());

    private static AnimationClip clip(String name, String expression) {
        return BedrockAnimationParser.parse(
                name,
                JsonParser.parseString(
                                "{\"loop\":true,\"animation_length\":1,\"bones\":{\"Head\":{\"rotation\":[0,0,"
                                        + expression
                                        + "]},\"Ear\":{\"rotation\":[0,0,10]}}}")
                        .getAsJsonObject());
    }

    private static AnimationClip appearanceClip(String name) {
        return BedrockAnimationParser.parse(
                name,
                JsonParser.parseString(
                                """
                                {
                                  "loop": true,
                                  "animation_length": 1,
                                  "timeline": {"0": "v.timeline_calls+=1;"},
                                  "bones": {
                                    "Head": {"rotation": [0, 0, 60], "position": [8, 0, 0]},
                                    "Ear": {"scale": ["q.anim_time", "q.anim_time", "q.anim_time"]}
                                  }
                                }
                                """)
                        .getAsJsonObject());
    }

    private static AnimationClip authoredLayerClip(String name, String bones, String timeline) {
        return BedrockAnimationParser.parse(
                name,
                JsonParser.parseString(
                                "{\"loop\":true,\"animation_length\":1,\"timeline\":{\"0\":\"v."
                                        + timeline
                                        + "+=1;\"},\"bones\":{" + bones + "}}")
                        .getAsJsonObject());
    }

    private static ParallelAnimationProgram program(String controllers, AnimationClip... clips) {
        return program(controllers, Map.of(), clips);
    }

    private static ParallelAnimationProgram program(
            String controllers, Map<String, String> functions, AnimationClip... clips) {
        var geometry = new GeometryDocument();
        var head = new GeometryDocument.Bone("Head");
        var ear = new GeometryDocument.Bone("Ear");
        ear.parentName("Head");
        geometry.add(head);
        geometry.add(ear);
        geometry.linkHierarchy();
        var animations = new LinkedHashMap<String, AnimationClip>();
        for (var clip : clips) animations.put(clip.name(), clip);
        return new ParallelAnimationProgram(
                "test",
                geometry,
                animations,
                BedrockAnimationControllerParser.parse(
                        JsonParser.parseString(controllers).getAsJsonObject()),
                functions,
                AuxiliaryBoneLayout.create(geometry),
                1,
                1);
    }

    private static double z(yesman.epicfight.api.utils.math.OpenMatrix4f matrix) {
        return Math.toDegrees(Math.atan2(matrix.m01, matrix.m00));
    }

    private static final String RULE =
            """
        {"overrides":[{"id":"main","source":{"kind":"controller","id":"model:player.main"}}]}
        """;
    private static final String SIMPLE =
            """
        {"animation_controllers":{"player.main":{"states":{"default":{"animations":["a"],
          "on_entry":["v.entries+=1; ysm.play_sound('test'); ysm.sync();"]}}}}}
        """;

    @Test
    void unselectedControllersDoNotRunLifecycleStatements() {
        var p = program(SIMPLE, clip("a", "30"));
        var e = new Env();
        var f =
                p.sampleUserRulesAt(
                        0, rules("{}"), ON, e, new ParallelAnimationProgram.UserMotionState());
        assertFalse(f.replaceEpicFightPose());
        assertEquals(0, e.value("v.entries"));
        assertEquals(0, e.effects);
    }

    @Test
    void explicitControllerCanOverrideDuringAttacksAndSuppressesEffects() {
        var p = program(SIMPLE, clip("a", "30"));
        var e = new Env();
        var f =
                p.sampleUserRulesAt(
                        0, rules(RULE), ON, e, new ParallelAnimationProgram.UserMotionState());
        assertTrue(f.replaceEpicFightPose());
        assertEquals(30, z(f.wholeModelDeltas()[0]), 1e-4);
        assertEquals(1, e.value("v.entries"));
        assertEquals(0, e.effects);
    }

    @Test
    void selectedStateTransitionsUseTheirAuthoredBlend() {
        var p =
                program(
                        """
          {"animation_controllers":{"player.main":{"states":{
            "default":{"animations":["a"],"transitions":[{"next":"v.next"}],"on_exit":["v.exits+=1;"]},
            "next":{"animations":["b"],"blend_transition":1,"on_entry":["v.entries+=1;"]}}}}}
          """,
                        clip("a", "30"),
                        clip("b", "70"));
        var e = new Env();
        var state = new ParallelAnimationProgram.UserMotionState();
        var r = rules(RULE);
        p.sampleUserRulesAt(0, r, ON, e, state);
        e.value("v.next", 1);
        assertEquals(30, z(p.sampleUserRulesAt(.1, r, ON, e, state).wholeModelDeltas()[0]), 1e-4);
        assertEquals(50, z(p.sampleUserRulesAt(.6, r, ON, e, state).wholeModelDeltas()[0]), 1e-4);
        assertEquals(70, z(p.sampleUserRulesAt(1.1, r, ON, e, state).wholeModelDeltas()[0]), 1e-4);
        assertEquals(1, e.value("v.entries"));
        assertEquals(1, e.value("v.exits"));
    }

    @Test
    void aControllerSharedByMasksRunsOncePerFrame() {
        var p = program(SIMPLE, clip("a", "30"));
        var e = new Env();
        var state = new ParallelAnimationProgram.UserMotionState();
        var r =
                rules(
                        """
          {"extras":[
            {"id":"head","source":{"kind":"controller","id":"player.main"},"target":{"bones":["Head"]}},
            {"id":"ear","source":{"kind":"controller","id":"player.main"},"target":{"bones":["Ear"]}}]}
          """);
        var f = p.sampleUserRulesAt(0, r, ON, e, state);
        assertFalse(f.replaceEpicFightPose());
        assertEquals(30, z(f.parallelDeltas()[0]), 1e-4);
        assertEquals(40, z(f.parallelDeltas()[1]), 1e-4);
        p.sampleUserRulesAt(0, r, ON, e, state);
        assertEquals(1, e.value("v.entries"));
    }

    @Test
    void declaredAppearanceControllerKeepsScaleOnlyAndSharesItsFrameWithExplicitExtra() {
        var p =
                program(
                        """
                        {"animation_controllers":{
                          "player.pre_parallel_1":{"states":{"default":{
                            "animations":["衣服切换d"],"on_entry":["v.entries+=1;"]}}},
                          "player.parallel_0":{"states":{"default":{
                            "animations":["形态切换"],"on_entry":["v.parallel_entries+=1;"]}}},
                          "player.main":{"states":{"default":{
                            "on_entry":["v.body_entries+=1;"]}}},
                          "player.right_hand":{"states":{"default":{
                            "on_entry":["v.hand_entries+=1;"]}}}
                        }}
                        """,
                        appearanceClip("衣服切换d"), appearanceClip("形态切换"));
        var e = new Env();
        var state = new ParallelAnimationProgram.UserMotionState();
        var r =
                rules(
                        """
                        {"extras":[{"id":"scale","source":{"kind":"controller",
                          "id":"player.pre_parallel_1"},"target":{"channels":["scale"]}}]}
                        """);

        var first = p.sampleUserRulesAt(0, r, OFF, e, state);
        assertFalse(first.replaceEpicFightPose());
        assertTrue(first.hiddenBones().contains("Ear"));
        assertEquals(0, z(first.parallelDeltas()[0]), 1e-4);
        assertEquals(0, first.parallelDeltas()[0].m30, 1e-6);
        assertEquals(1, e.value("v.entries"));
        assertEquals(2, e.value("v.timeline_calls"));
        assertEquals(1, e.value("v.parallel_entries"));
        assertEquals(0, e.value("v.body_entries"));
        assertEquals(0, e.value("v.hand_entries"));

        var later = p.sampleUserRulesAt(.5, r, OFF, e, state);
        assertEquals(.5, later.parallelDeltas()[1].m00, 1e-4);
        assertEquals(0, z(later.parallelDeltas()[0]), 1e-4);
        assertEquals(0, later.parallelDeltas()[0].m30, 1e-6);
        assertEquals(1, e.value("v.entries"));
        assertEquals(2, e.value("v.timeline_calls"));
        assertEquals(1, e.value("v.parallel_entries"));
    }

    @Test
    void associatedLayersIncludeAppearanceControllerChannelsInPreAndPostOrder() {
        var p =
                program(
                        """
                        {"animation_controllers":{
                          "player.pre_parallel_1":{"states":{"default":{
                            "animations":["衣服切换d"],"on_entry":["v.pre_entries+=1;"]}}},
                          "player.parallel_0":{"states":{"default":{
                            "animations":["形态切换"],"on_entry":["v.post_entries+=1;"]}}},
                          "player.main":{"states":{"default":{
                            "animations":["authored"],"on_entry":["v.main_entries+=1;"]}}}
                        }}
                        """,
                        authoredLayerClip("衣服切换d", "\"Head\":{\"position\":[2,0,0]}", "pre_timeline"),
                        authoredLayerClip("形态切换", "\"Ear\":{\"rotation\":[0,0,20]}", "post_timeline"),
                        authoredLayerClip("authored", "\"Head\":{\"rotation\":[0,0,30]}", "main_timeline"),
                        authoredLayerClip("armor_head", "\"Ear\":{\"rotation\":[0,0,7]}", "armor_timeline"));
        var e = new Env();
        var state = new ParallelAnimationProgram.UserMotionState();
        var mainProvider = new AutomaticAnimationSelector.ActiveClip("idle", 0, false);
        state.nativeProviders = new AutomaticAnimationSelector.Selection(
                List.of(mainProvider,
                        new AutomaticAnimationSelector.ActiveClip("armor_head", .25, false)),
                mainProvider, null, Set.of());
        var r =
                rules(
                        """
                        {"overrides":[{"id":"body","source":{"kind":"controller",
                          "id":"player.main","include_associated_layers":true}}]}
                        """);

        var frame = p.sampleUserRulesAt(0, r, OFF, e, state);
        assertTrue(frame.replaceEpicFightPose());
        assertEquals(-0.125, frame.wholeModelDeltas()[0].m30, 1e-4);
        assertEquals(30, z(frame.wholeModelDeltas()[0]), 1e-4);
        assertEquals(57, z(frame.wholeModelDeltas()[1]), 1e-4);
        assertEquals(1, e.value("v.pre_entries"));
        assertEquals(1, e.value("v.post_entries"));
        assertEquals(1, e.value("v.main_entries"));
        assertEquals(1, e.value("v.pre_timeline"));
        assertEquals(1, e.value("v.post_timeline"));
        assertEquals(1, e.value("v.main_timeline"));
        assertEquals(1, e.value("v.armor_timeline"));

        p.sampleUserRulesAt(.5, r, OFF, e, state);
        assertEquals(1, e.value("v.pre_entries"));
        assertEquals(1, e.value("v.post_entries"));
        assertEquals(1, e.value("v.main_entries"));
        assertEquals(1, e.value("v.pre_timeline"));
        assertEquals(1, e.value("v.post_timeline"));
        assertEquals(1, e.value("v.main_timeline"));
    }

    @Test
    void inactiveControllersRestartTheirStateAndTimelinesOnReactivation() {
        var animated =
                BedrockAnimationParser.parse(
                        "a",
                        JsonParser.parseString(
                                        """
          {"animation_length":1,"timeline":{"0":"v.samples+=1;"},"bones":{"Head":{"rotation":[0,0,20]}}}
          """)
                                .getAsJsonObject());
        var p = program(SIMPLE, animated);
        var e = new Env();
        var state = new ParallelAnimationProgram.UserMotionState();
        var r =
                rules(
                        """
          {"extras":[{"id":"head","when":{"attacking":true},"source":{"kind":"controller","id":"player.main"}}]}
          """);
        p.sampleUserRulesAt(0, r, ON, e, state);
        p.sampleUserRulesAt(.2, r, OFF, e, state);
        p.sampleUserRulesAt(.4, r, ON, e, state);
        assertEquals(2, e.value("v.entries"));
        assertEquals(2, e.value("v.samples"));
    }

    @Test
    void emptyStatesRetainEfUntilTheyActuallyEmitAPose() {
        var p =
                program(
                        """
          {"animation_controllers":{"player.main":{"states":{
            "default":{"transitions":[{"next":"v.next"}]},"next":{"animations":["a"]}}}}}
          """,
                        clip("a", "30"));
        var e = new Env();
        var state = new ParallelAnimationProgram.UserMotionState();
        assertFalse(p.sampleUserRulesAt(0, rules(RULE), ON, e, state).replaceEpicFightPose());
        e.value("v.next", 1);
        assertTrue(p.sampleUserRulesAt(.1, rules(RULE), ON, e, state).replaceEpicFightPose());
    }

    @Test
    void stateVariablesAndPerClipTimeReachTheSamePoseEvaluator() {
        var p =
                program(
                        """
          {"animation_controllers":{"player.main":{"states":{"default":{"animations":["a"],
            "variables":{"angle":{"input":"v.input"}}}}}}}
          """,
                        clip("a", "\"v.angle + q.anim_time * 60\""));
        var e = new Env();
        e.value("v.input", 10);
        var state = new ParallelAnimationProgram.UserMotionState();
        var r = rules(RULE);
        p.sampleUserRulesAt(10, r, ON, e, state);
        assertEquals(40, z(p.sampleUserRulesAt(10.5, r, ON, e, state).wholeModelDeltas()[0]), 1e-4);
    }

    @Test
    void nativeBuiltinMainUsesTheLocalIdleFallback() {
        var p = program(
                """
                {"animation_controllers":{"player.main":{"initial_state":"ysm-builtin",
                  "states":{"ysm-builtin":{}}}}}
                """,
                clip("idle", "20"));
        var state = new ParallelAnimationProgram.UserMotionState();
        var frame = p.sampleUserRulesAt(0, rules(RULE), OFF, new Env(), state);
        assertTrue(frame.replaceEpicFightPose());
        assertEquals(20, z(frame.wholeModelDeltas()[0]), 1e-4);
        assertFalse(state.diagnostic.contains("currently emits no pose"));
    }

    @Test
    void controllerReferenceToParallelClipSharesAuthoredClockAndTimeline() {
        var parallel = BedrockAnimationParser.parse(
                "parallel3",
                JsonParser.parseString(
                        """
                        {"loop":true,"animation_length":1,
                         "timeline":{"0":"v.shared_timeline+=1;"},
                         "bones":{"Head":{"rotation":[0,0,"q.anim_time*60"]}}}
                        """).getAsJsonObject());
        var p = program(
                """
                {"animation_controllers":{"player.main":{"states":{"default":{
                  "animations":["parallel3"],"on_entry":["v.entries+=1;"]}}}}}
                """,
                parallel);
        var e = new Env();
        var state = new ParallelAnimationProgram.UserMotionState();
        var r = rules(RULE);
        var first = p.sampleUserRulesAt(0, r, OFF, e, state);
        assertTrue(first.replaceEpicFightPose());
        assertEquals(0, z(first.wholeModelDeltas()[0]), 1e-4);
        assertEquals(1, e.value("v.shared_timeline"));
        assertEquals(1, e.value("v.entries"));

        var later = p.sampleUserRulesAt(.5, r, OFF, e, state);
        assertEquals(30, z(later.wholeModelDeltas()[0]), 1e-4);
        assertEquals(1, e.value("v.shared_timeline"));
        assertEquals(1, e.value("v.entries"));
    }

    @Test
    void scriptSlotAliasesShareOneHookAcrossUserAndLegacyPaths() {
        var functions = Map.of(
                "compact@player_ctrl_parallel0",
                "v.compact_calls+=1;ctrl.set_animation('compact_pose');return ctrl.state_continue;",
                "underscored@player_ctrl_parallel_0",
                "v.underscored_calls+=1;ctrl.set_animation('underscored_pose');return ctrl.state_continue;");
        var p = program(
                "{}",
                functions,
                clip("compact_pose", "q.anim_time*20"),
                clip("underscored_pose", "q.anim_time*40"));
        var e = new Env();
        e.scripts = new MolangScriptRuntime(functions, Map.of(
                "compact_pose", new MolangScriptRuntime.Clip(1, AnimationClip.Playback.REPEAT),
                "underscored_pose", new MolangScriptRuntime.Clip(1, AnimationClip.Playback.REPEAT)));
        var state = new ParallelAnimationProgram.UserMotionState();
        var r = rules(
                """
                {"overrides":[{"id":"slot","source":{"kind":"controller","id":"player.parallel_0"}}]}
                """);

        var first = p.sampleUserRulesAt(0, r, OFF, e, state);
        assertTrue(first.replaceEpicFightPose());
        assertEquals(1, e.value("v.compact_calls") + e.value("v.underscored_calls"));

        p.sampleUserRulesAt(.1, rules("{}"), OFF, e, state);
        var resumed = p.sampleUserRulesAt(.2, r, OFF, e, state);
        assertTrue(resumed.replaceEpicFightPose());
        // Native appearance remains active even while no explicit override uses it.
        assertEquals(3, e.value("v.compact_calls") + e.value("v.underscored_calls"));
        assertTrue((e.value("v.compact_calls") == 3 && e.value("v.underscored_calls") == 0)
                || (e.value("v.compact_calls") == 0 && e.value("v.underscored_calls") == 3));

        var outputs = new LinkedHashMap<String, MolangScriptRuntime.Output>();
        var emptySelection = new AutomaticAnimationSelector.Selection(
                List.of(), null, null, Set.of());
        p.selectScriptControllers(emptySelection, e.scripts, outputs, e, .3, .3);
        assertEquals(1, outputs.size());
        assertEquals(4, e.value("v.compact_calls") + e.value("v.underscored_calls"));
    }

    @Test
    void pausedScriptSlotKeepsItsLastPoseWithoutAdvancingClipExpressions() {
        var functions = Map.of(
                "pause@player_ctrl_parallel_0",
                "ctrl.set_beginning_transition_length(0.1);v.pause ? return ctrl.state_pause;"
                        + "ctrl.set_animation('paused_pose');return ctrl.state_continue;");
        var p = program("{}", functions, clip("paused_pose", "\"v.pose_samples+=1\""));
        var e = new Env();
        e.scripts = new MolangScriptRuntime(functions, Map.of(
                "paused_pose", new MolangScriptRuntime.Clip(1, AnimationClip.Playback.REPEAT)));
        var state = new ParallelAnimationProgram.UserMotionState();
        var r = rules(
                """
                {"overrides":[{"id":"slot","source":{"kind":"controller","id":"player.parallel0"}}]}
                """);

        var playing = p.sampleUserRulesAt(0, r, OFF, e, state);
        assertTrue(playing.replaceEpicFightPose());
        assertEquals(1, e.value("v.pose_samples"));
        e.value("v.pause", 1);
        var paused = p.sampleUserRulesAt(.05, r, OFF, e, state);
        assertTrue(paused.replaceEpicFightPose());
        assertEquals(1, e.value("v.pose_samples"));
        assertEquals(z(playing.wholeModelDeltas()[0]), z(paused.wholeModelDeltas()[0]), 1e-4);
    }

    @Test
    void controllerStateIsIsolatedBetweenEntities() {
        var p = program(SIMPLE, clip("a", "\"v.input\""));
        var a = new Env();
        var b = new Env();
        a.value("v.input", 20);
        b.value("v.input", 50);
        assertEquals(
                20,
                z(
                        p.sampleUserRulesAt(
                                        0,
                                        rules(RULE),
                                        ON,
                                        a,
                                        new ParallelAnimationProgram.UserMotionState())
                                .wholeModelDeltas()[0]),
                1e-4);
        assertEquals(
                50,
                z(
                        p.sampleUserRulesAt(
                                        0,
                                        rules(RULE),
                                        ON,
                                        b,
                                        new ParallelAnimationProgram.UserMotionState())
                                .wholeModelDeltas()[0]),
                1e-4);
    }

    @Test
    void unsupportedControllerClockIsAConfigurationError() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        rules(
                                """
          {"extras":[{"id":"x","source":{"kind":"controller","id":"player.main"},"playback":{"clock":"authored"}}]}
          """));
    }

    @Test
    void shadowedControllersNeverRunStateEffects() {
        var p = program(SIMPLE, clip("a", "30"));
        var env = new Env();
        var r =
                rules(
                        """
            {"overrides":[{"id":"winner","priority":10,"source":{"id":"a"}},
             {"id":"shadowed","source":{"kind":"controller","id":"player.main"}}]}
            """);
        p.sampleUserRulesAt(0, r, ON, env, new ParallelAnimationProgram.UserMotionState());
        assertEquals(0, env.value("v.entries"));
    }

    @Test
    void controllersWithNoGeometryCanInitializeOtherSelectedClips() {
        var init =
                BedrockAnimationParser.parse(
                        "initialize",
                        JsonParser.parseString(
                                        """
            {"bones":{"Molang":{"rotation":["v.angle=45",0,0]}}}
            """)
                                .getAsJsonObject());
        var p =
                program(
                        """
            {"animation_controllers":{"player.main":{"states":{"default":{"animations":["initialize","a"]}}}}}
            """,
                        init,
                        clip("a", "\"v.angle\""));
        var frame =
                p.sampleUserRulesAt(
                        0,
                        rules(RULE),
                        ON,
                        new Env(),
                        new ParallelAnimationProgram.UserMotionState());
        assertEquals(45, z(frame.wholeModelDeltas()[0]), 1e-4);
    }

    @Test
    void variableOnlyControllerKeepsEfAndRunsOnlyWhenExplicitlySelected() {
        var p =
                program(
                        """
            {"animation_controllers":{"player.main":{"states":{"default":{"on_entry":["v.form=1;"]}}}}}
            """,
                        clip("a", "30"));
        var env = new Env();
        var frame =
                p.sampleUserRulesAt(
                        0, rules(RULE), ON, env, new ParallelAnimationProgram.UserMotionState());
        assertFalse(frame.replaceEpicFightPose());
        assertEquals(1, env.value("v.form"));
    }

    @Test
    void fadeInStartsTheControllerClockAtActivationRatherThanAtFirstVisibleWeight() {
        var p = program(SIMPLE, clip("a", "\"q.anim_time*60\""));
        var r =
                rules(
                        """
            {"extras":[{"id":"head","source":{"kind":"controller","id":"player.main"},
              "transition":{"enter_seconds":1},"target":{"bones":["Head"]}}]}
            """);
        var env = new Env();
        var state = new ParallelAnimationProgram.UserMotionState();
        p.sampleUserRulesAt(0, r, ON, env, state);
        assertEquals(15, z(p.sampleUserRulesAt(.5, r, ON, env, state).parallelDeltas()[0]), 1e-4);
        assertEquals(1, env.value("v.entries"));
    }
}
