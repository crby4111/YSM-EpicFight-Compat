package net.okitsu.ysmepicfightcompat.animation;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;

import net.okitsu.ysmepicfightcompat.geometry.GeometryDocument;
import net.okitsu.ysmepicfightcompat.mesh.AuxiliaryBoneLayout;
import net.okitsu.ysmepicfightcompat.mesh.AuxiliaryPoseMatrices;
import net.okitsu.ysmepicfightcompat.mesh.HumanoidRig;

import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.*;
import yesman.epicfight.api.animation.Joint;
import yesman.epicfight.api.animation.JointTransform;
import yesman.epicfight.api.animation.Pose;
import yesman.epicfight.api.model.Armature;
import yesman.epicfight.api.utils.math.OpenMatrix4f;
import yesman.epicfight.api.utils.math.Vec3f;
import yesman.epicfight.api.utils.math.Vec4f;

/** Tests real parser/evaluator/matrix output, not only rule selection. */
class UserMotionProgramTest {
    private static final class Env implements ClipTimeEnvironment {
        int externalCalls;
        final Map<Integer, Double> values = new HashMap<>(), queries = new HashMap<>();

        public double readVariable(int s) {
            return values.getOrDefault(s, 0d);
        }

        public boolean hasVariable(int s) {
            return values.containsKey(s);
        }

        public void writeVariable(int s, double v) {
            values.put(s, v);
        }

        public double readQuery(int s) {
            return queries.getOrDefault(s, 0d);
        }

        public double invoke(String n, double[] a) {
            return 0;
        }

        public double invokeWithText(String n, String[] a) {
            externalCalls++;
            return 0;
        }

        public void clipTime(double time) {
            queries.put(ExpressionEngine.querySlot("query.anim_time"), time);
        }
    }

    private static MotionRuleSet rules(String source) {
        return MotionRuleSet.parse(JsonParser.parseString(source).getAsJsonObject());
    }

    private static MotionRuleSet.Facts facts(boolean attacking) {
        return new MotionRuleSet.Facts(
                Map.of("model", "test", "movement", "swim", "attacking", String.valueOf(attacking)),
                Set.of());
    }

    private static MotionRuleSet.Facts facts(String movement) {
        return new MotionRuleSet.Facts(
                Map.of("model", "test", "movement", movement, "attacking", "false"), Set.of());
    }

    private static AnimationClip clip(String name, String bones) {
        return BedrockAnimationParser.parse(
                name,
                JsonParser.parseString(
                                "{\"loop\":true,\"animation_length\":1,\"bones\":{" + bones + "}}")
                        .getAsJsonObject());
    }

    private static ParallelAnimationProgram program(AnimationClip... clips) {
        var g = new GeometryDocument();
        var h = new GeometryDocument.Bone("Head");
        var ear = new GeometryDocument.Bone("Ear");
        ear.parentName("Head");
        g.add(h);
        g.add(ear);
        g.linkHierarchy();
        var map = new LinkedHashMap<String, AnimationClip>();
        for (var c : clips) map.put(c.name(), c);
        return new ParallelAnimationProgram(g, map, AuxiliaryBoneLayout.create(g), 1, 1);
    }

    private static double z(yesman.epicfight.api.utils.math.OpenMatrix4f m) {
        return Math.toDegrees(Math.atan2(m.m01, m.m00));
    }

    @Test
    void emptyRulesKeepEfEvenWhenAuthorParallelWouldMoveTheBody() {
        var p =
                program(
                        clip(
                                "parallel3",
                                "\"Head\":{\"rotation\":[20,0,0]},\"Ear\":{\"rotation\":[0,0,30]}"));
        var frame =
                p.sampleUserRulesAt(
                        .2,
                        rules("{}"),
                        facts(true),
                        new Env(),
                        new ParallelAnimationProgram.UserMotionState());
        assertFalse(frame.replaceEpicFightPose());
        assertEquals(1, frame.parallelDeltas()[0].m11, 1e-6);
        assertEquals(0, z(frame.parallelDeltas()[1]), 1e-6);
    }

    @Test
    void emptyRuleRootScaleTransportKeepsFourAuthoredArmSeamsAndRootSkin() {
        var sample = rootScaleSample(0.9F);
        var root = sample.layout().entryForBoneName("Root");
        var rootDelta = sample.frame().parallelDeltas()[root.auxiliaryIndex()];
        assertEquals(0.9F, rootDelta.m00, 0.00001F);
        assertEquals(0.9F, rootDelta.m11, 0.00001F);
        assertEquals(0.9F, rootDelta.m22, 0.00001F);
        assertFalse(sample.frame().replaceEpicFightPose());
        assertTrue(sample.frame().hiddenBones().isEmpty());

        Armature armature = rotatedHumanoidArmature();
        Pose pose = new Pose();
        pose.putJointData("Root", JointTransform.rotation(new Quaternionf()
                .rotateXYZ(radians(17), radians(-11), radians(9))));
        pose.putJointData("Shoulder_R", JointTransform.rotation(new Quaternionf()
                .rotateXYZ(radians(-23), radians(12), radians(34))));
        pose.putJointData("Arm_R", JointTransform.rotation(new Quaternionf()
                .rotateXYZ(radians(31), radians(-18), radians(25))));
        pose.putJointData("Hand_R", JointTransform.rotation(new Quaternionf()
                .rotateXYZ(radians(47), radians(8), radians(-21))));
        pose.putJointData("Elbow_R", JointTransform.rotation(new Quaternionf()
                .rotateXYZ(radians(-19), radians(27), radians(16))));
        pose.putJointData("Shoulder_L", JointTransform.rotation(new Quaternionf()
                .rotateXYZ(radians(15), radians(-28), radians(-31))));
        pose.putJointData("Arm_L", JointTransform.rotation(new Quaternionf()
                .rotateXYZ(radians(-34), radians(14), radians(22))));
        pose.putJointData("Hand_L", JointTransform.rotation(new Quaternionf()
                .rotateXYZ(radians(-52), radians(-9), radians(18))));
        pose.putJointData("Elbow_L", JointTransform.rotation(new Quaternionf()
                .rotateXYZ(radians(21), radians(-24), radians(-13))));
        armature.setPose(pose);
        OpenMatrix4f[] poses = armature.getPoseMatrices();

        OpenMatrix4f[] composed = new AuxiliaryPoseMatrices(sample.layout()).compose(
                armature, poses, sample.frame().parallelDeltas(),
                sample.frame().wholeModelDeltas(), sample.frame().heldItemDeltas(),
                sample.frame().replaceEpicFightPose(),
                sample.frame().replaceEpicFightAnchors(),
                sample.frame().suppressParallelDeltas(),
                sample.frame().heldItemAnchorJoints());
        assertNotNull(composed);

        OpenMatrix4f rootAnchor = new OpenMatrix4f(poses[HumanoidRig.ROOT])
                .mulBack(armature.searchJointById(HumanoidRig.ROOT).getToOrigin());
        OpenMatrix4f expectedRootSkin = new OpenMatrix4f(rootAnchor).mulBack(rootDelta);
        assertMatrixNear(expectedRootSkin,
                composed[root.poseIndex()], 0.00002F);

        assertSeam(sample.layout(), composed, "MRightArm", "RightArm",
                new Vector3f(-0.3F, 1.5F, 0.0F));
        assertSeam(sample.layout(), composed, "RightArm", "RightForeArm",
                new Vector3f(-0.3F, 1.05F, 0.0F));
        assertSeam(sample.layout(), composed, "MLeftArm", "LeftArm",
                new Vector3f(0.3F, 1.5F, 0.0F));
        assertSeam(sample.layout(), composed, "LeftArm", "LeftForeArm",
                new Vector3f(0.3F, 1.05F, 0.0F));
    }

    @Test
    void collapsedAppearanceRootRemainsHiddenFiniteAndUnresurrected() {
        var sample = rootScaleSample(0.0F);
        assertTrue(sample.frame().hiddenBones().containsAll(Set.of(
                "Root", "MRightArm", "RightArm", "RightForeArm",
                "MLeftArm", "LeftArm", "LeftForeArm")));

        Armature armature = rotatedHumanoidArmature();
        Pose pose = new Pose();
        pose.putJointData("Root", JointTransform.rotation(new Quaternionf()
                .rotateXYZ(radians(17), radians(-11), radians(9))));
        pose.putJointData("Shoulder_R", JointTransform.rotation(new Quaternionf()
                .rotateXYZ(radians(-23), radians(12), radians(34))));
        pose.putJointData("Hand_R", JointTransform.rotation(new Quaternionf()
                .rotateXYZ(radians(47), radians(8), radians(-21))));
        pose.putJointData("Shoulder_L", JointTransform.rotation(new Quaternionf()
                .rotateXYZ(radians(15), radians(-28), radians(-31))));
        pose.putJointData("Hand_L", JointTransform.rotation(new Quaternionf()
                .rotateXYZ(radians(-52), radians(-9), radians(18))));
        armature.setPose(pose);
        OpenMatrix4f[] composed = new AuxiliaryPoseMatrices(sample.layout()).compose(
                armature, armature.getPoseMatrices(), sample.frame().parallelDeltas(),
                sample.frame().wholeModelDeltas(), sample.frame().heldItemDeltas(),
                sample.frame().replaceEpicFightPose(),
                sample.frame().replaceEpicFightAnchors(),
                sample.frame().suppressParallelDeltas(),
                sample.frame().heldItemAnchorJoints());
        assertNotNull(composed);

        for (String name : new String[]{"Root", "RightArm", "RightForeArm",
                "LeftArm", "LeftForeArm"}) {
            var entry = sample.layout().entryForBoneName(name);
            OpenMatrix4f skin = composed[entry.poseIndex()];
            assertFinite(skin);
            assertEquals(0.0F, linearDeterminant(skin), 0.000001F, name);
        }
    }

    @Test
    void explicitAttackOverrideWritesTheBodyAndExitsToEf() {
        var p = program(clip("pose", "\"Head\":{\"rotation\":[20,0,0]}"));
        var rules =
                rules(
                        """
        {"overrides":[{"id":"attack","when":{"attacking":true},"source":{"id":"model:pose"}}]}
        """);
        var state = new ParallelAnimationProgram.UserMotionState();
        var on = p.sampleUserRulesAt(0, rules, facts(true), new Env(), state);
        assertTrue(on.replaceEpicFightPose());
        assertEquals(Math.cos(Math.toRadians(20)), on.wholeModelDeltas()[0].m11, 1e-6);
        var off = p.sampleUserRulesAt(1, rules, facts(false), new Env(), state);
        assertFalse(off.replaceEpicFightPose());
        assertEquals(1, off.wholeModelDeltas()[0].m11, 1e-6);
    }

    @Test
    void additiveAndReplaceHaveDifferentObservableResults() {
        for (String mode : List.of("additive", "replace_local_channels")) {
            var p =
                    program(
                            clip("first", "\"Ear\":{\"rotation\":[0,0,30]}"),
                            clip("second", "\"Ear\":{\"rotation\":[0,0,10]}"));
            var r =
                    rules(
                            """
            {"extras":[{"id":"first","source":{"id":"model:first"}},
            {"id":"second","source":{"id":"model:second"},"blend":{"mode":"%s"}}]}
            """
                                    .formatted(mode));
            var frame =
                    p.sampleUserRulesAt(
                            0,
                            r,
                            facts(true),
                            new Env(),
                            new ParallelAnimationProgram.UserMotionState());
            assertEquals(mode.equals("additive") ? 40 : 10, z(frame.parallelDeltas()[1]), 1e-4);
            assertFalse(frame.replaceEpicFightPose());
        }
    }

    @Test
    void boneAndChannelMaskPreventBodyAndPositionLeakage() {
        var p =
                program(
                        clip(
                                "pose",
                                "\"Head\":{\"rotation\":[40,0,0]},\"Ear\":{\"rotation\":[0,0,30],\"position\":[5,0,0]}"));
        var r =
                rules(
                        """
        {"extras":[{"id":"ear","source":{"id":"model:pose"},"target":{"bones":["Ea*"],"channels":["rotation"]}}]}
        """);
        var f =
                p.sampleUserRulesAt(
                        0,
                        r,
                        facts(true),
                        new Env(),
                        new ParallelAnimationProgram.UserMotionState());
        assertEquals(1, f.parallelDeltas()[0].m11, 1e-6);
        assertEquals(30, z(f.parallelDeltas()[1]), 1e-4);
        assertEquals(0, f.parallelDeltas()[1].m30, 1e-6);
    }

    @Test
    void associatedInitScaleCanBeUndoneByTheSelectedMainPose() {
        var p =
                program(
                        clip("pre_parallel0", "\"Ear\":{\"scale\":[0,0,0]}"),
                        clip("swim", "\"Ear\":{\"scale\":[1,1,1]}"));
        var r =
                rules(
                        """
        {"overrides":[{"id":"swim","source":{"kind":"authored_state","state":"swim","head_tracking":"none"}}]}
        """);
        var f =
                p.sampleUserRulesAt(
                        0,
                        r,
                        facts(false),
                        new Env(),
                        new ParallelAnimationProgram.UserMotionState());
        assertTrue(f.replaceEpicFightPose());
        assertFalse(f.hiddenBones().contains("Ear"));
        assertEquals(1, f.wholeModelDeltas()[1].m00, 1e-6);
    }

    @Test
    void appearanceIsRetainedWithoutGrantingAutomaticMotionOwnership() {
        var p =
                program(
                        clip(
                                "pre_parallel0",
                                "\"Ear\":{\"scale\":[0,0,0]},\"Head\":{\"rotation\":[60,0,0]}"));
        var f =
                p.sampleUserRulesAt(
                        0,
                        rules("{}"),
                        facts(false),
                        new Env(),
                        new ParallelAnimationProgram.UserMotionState());
        assertFalse(f.replaceEpicFightPose());
        assertTrue(f.hiddenBones().contains("Ear"));
        assertEquals(1, f.parallelDeltas()[0].m11, 1e-6);
    }

    @Test
    void sharedParallelStateIsEvaluatedOnceOnRepeatedAndMaskedDraws() {
        var p =
                program(
                        clip(
                                "parallel3",
                                "\"molang\":{\"rotation\":[\"v.calls+=1\",0,0]},\"Ear\":{\"rotation\":[0,0,30]}"));
        var r =
                rules(
                        """
        {"extras":[{"id":"a","source":{"id":"model:parallel3"},"target":{"bones":["Ear"]}}]}
        """);
        var e = new Env();
        var state = new ParallelAnimationProgram.UserMotionState();
        p.sampleUserRulesAt(.2, r, facts(false), e, state);
        p.sampleUserRulesAt(.2, r, facts(false), e, state);
        assertEquals(1, e.readVariable(ExpressionEngine.slot("v.calls")));
        p.sampleUserRulesAt(.3, r, facts(false), e, state);
        assertEquals(2, e.readVariable(ExpressionEngine.slot("v.calls")));
    }

    @Test
    void nativeHeadPostIsExplicitAndAppliedOnce() {
        var queries = new Env();
        queries.queries.put(ExpressionEngine.querySlot("ysm.head_pitch"), 30d);
        queries.queries.put(ExpressionEngine.querySlot("ysm.head_yaw"), 20d);

        for (String movement : List.of("swim", "water_idle")) {
            String clipName = movement.equals("water_idle") ? "swim_stand" : movement;
            var animation = clip(
                    clipName, "\"Head\":{\"rotation\":[\"ysm.head_pitch\",0,0]}");
            var tracked = program(animation).sampleUserRulesAt(
                    0,
                    rules("""
                            {"overrides":[{"id":"water","source":{"kind":"authored_state",
                              "state":"%s"}}]}
                            """.formatted(movement)),
                    facts(movement), queries,
                    new ParallelAnimationProgram.UserMotionState());
            var baseline = program(animation).sampleUserRulesAt(
                    0,
                    rules("""
                            {"overrides":[{"id":"water","source":{"kind":"authored_state",
                              "state":"%s","head_tracking":"none"}}]}
                            """.formatted(movement)),
                    facts(movement), queries,
                    new ParallelAnimationProgram.UserMotionState());
            assertMatrixNear(baseline.wholeModelDeltas()[0],
                    tracked.wholeModelDeltas()[0], 1e-4F);
            assertEquals((float) Math.cos(Math.toRadians(30)),
                    tracked.wholeModelDeltas()[0].m11, 1e-4F, movement);
        }

        var authoredPitch = program(clip(
                "walk", "\"Head\":{\"rotation\":[\"ysm.head_pitch\",0,0]}"));
        var nativeWalk = authoredPitch.sampleUserRulesAt(
                0,
                rules("""
                        {"overrides":[{"id":"walk","source":{"kind":"authored_state",
                          "state":"walk"}}]}
                        """),
                facts("walk"), queries, new ParallelAnimationProgram.UserMotionState());
        var authoredBothAxes = program(clip(
                // Bedrock Y rotation is negated at import; the native post is already model-space.
                "walk", "\"Head\":{\"rotation\":[\"ysm.head_pitch\",\"-ysm.head_yaw\",0]}"));
        var expectedWalk = authoredBothAxes.sampleUserRulesAt(
                0,
                rules("""
                        {"overrides":[{"id":"walk","source":{"kind":"authored_state",
                          "state":"walk","head_tracking":"none"}}]}
                        """),
                facts("walk"), queries, new ParallelAnimationProgram.UserMotionState());
        assertMatrixNear(expectedWalk.wholeModelDeltas()[0],
                nativeWalk.wholeModelDeltas()[0], 1e-4F);
    }

    @Test
    void missingMaskIsDiagnosedAndDoesNotDisableOtherExtraLayers() {
        var p = program(clip("pose", "\"Ear\":{\"rotation\":[0,0,20]}"));
        var r =
                rules(
                        """
        {"extras":[{"id":"bad","source":{"id":"pose"},"target":{"bones":["Missing"]}},{"id":"ok","source":{"id":"pose"}}]}
        """);
        var state = new ParallelAnimationProgram.UserMotionState();
        var f = p.sampleUserRulesAt(0, r, facts(false), new Env(), state);
        assertEquals(20, z(f.parallelDeltas()[1]), 1e-4);
        assertTrue(state.diagnostic.contains("bone mask matched no bones"));
    }

    @Test
    void efClockSamplesNormalizedProgressAndMissingClockAllowsTheNextOverride() {
        AnimationClip timed =
                BedrockAnimationParser.parse(
                        "timed",
                        JsonParser.parseString(
                                        """
                {"animation_length":2,"bones":{"Ear":{"rotation":{"0":[0,0,0],"2":[0,0,80]}}}}
                """)
                                .getAsJsonObject());
        var p = program(timed, clip("fallback", "\"Ear\":{\"rotation\":[0,0,15]}"));
        var r =
                rules(
                        """
                {"overrides":[
                  {"id":"synced","priority":20,"source":{"id":"timed"},
                   "playback":{"clock":"ef","ef_animation":"test:attack"}},
                  {"id":"fallback","source":{"id":"fallback"}}
                ]}
                """);
        var sample =
                new MotionRuleFacts.Sample(
                        facts(true), Map.of("test:attack", new MotionRuleFacts.Clock(1, 4)));
        var frame =
                p.sampleUserRulesAt(
                        100,
                        false,
                        r,
                        sample,
                        new Env(),
                        new ParallelAnimationProgram.UserMotionState());
        assertEquals(20, z(frame.wholeModelDeltas()[1]), 1e-4);
        var fallback =
                p.sampleUserRulesAt(
                        101,
                        r,
                        facts(true),
                        new Env(),
                        new ParallelAnimationProgram.UserMotionState());
        assertEquals(15, z(fallback.wholeModelDeltas()[1]), 1e-4);
    }

    @Test
    void ownershipFadeStartsAtTheDisplayedSourceAndDecreasesItsWeight() {
        var p = program(clip("pose", "\"Ear\":{\"rotation\":[0,0,80]}"));
        var r =
                rules(
                        """
                {"overrides":[{"id":"pose","when":{"attacking":true},
                  "source":{"id":"pose"},
                  "transition":{"enter_seconds":1,"exit_seconds":1}}]}
                """);
        var state = new ParallelAnimationProgram.UserMotionState();
        state.lastSkin =
                new yesman.epicfight.api.utils.math.OpenMatrix4f[] {
                    new yesman.epicfight.api.utils.math.OpenMatrix4f(),
                    new yesman.epicfight.api.utils.math.OpenMatrix4f()
                };
        var start = p.sampleUserRulesAt(10, r, facts(true), new Env(), state);
        assertEquals(1, start.fullBodyBlendWeight());
        var middle = p.sampleUserRulesAt(10.5, r, facts(true), new Env(), state);
        assertEquals(.5, middle.fullBodyBlendWeight());
        var finish = p.sampleUserRulesAt(11, r, facts(true), new Env(), state);
        assertNull(finish.fullBodyBlendSource());
    }

    @Test
    void firstPersonOverrideRequestsItsViewRebaseAndDropsOtherViewSnapshots() {
        var p = program(clip("pose", "\"Head\":{\"rotation\":[20,0,0]}"));
        var r =
                rules(
                        """
                {"overrides":[{"id":"pose","source":{"id":"pose"},
                  "transition":{"enter_seconds":1,"exit_seconds":1}}]}
                """);
        var state = new ParallelAnimationProgram.UserMotionState();
        var frame =
                p.sampleUserRulesAt(
                        1,
                        true,
                        r,
                        new MotionRuleFacts.Sample(facts(false), Map.of()),
                        new Env(),
                        state);
        assertTrue(frame.customFullBodyPose());
        assertTrue(frame.replaceEpicFightPose());
        assertNull(frame.fullBodyBlendSource());
        var ef =
                p.sampleUserRulesAt(
                        2,
                        true,
                        rules("{}"),
                        new MotionRuleFacts.Sample(facts(false), Map.of()),
                        new Env(),
                        state);
        assertFalse(ef.customFullBodyPose());
        assertFalse(ef.replaceEpicFightPose());
    }

    @Test
    void efSyncedExtraFadesItsLastPoseAfterTheClockDisappears() {
        var p = program(clip("pose", "\"Ear\":{\"rotation\":[0,0,40]}"));
        var r =
                rules(
                        """
                {"extras":[{"id":"cape","source":{"id":"pose"},
                  "playback":{"clock":"ef","ef_animation":"test:attack"},
                  "transition":{"exit_seconds":1}}]}
                """);
        var state = new ParallelAnimationProgram.UserMotionState();
        var clock =
                new MotionRuleFacts.Sample(
                        facts(true), Map.of("test:attack", new MotionRuleFacts.Clock(.5, 1)));
        p.sampleUserRulesAt(0, false, r, clock, new Env(), state);
        p.sampleUserRulesAt(1, r, facts(false), new Env(), state);
        var fading = p.sampleUserRulesAt(1.5, r, facts(false), new Env(), state);
        assertEquals(20, z(fading.parallelDeltas()[1]), 1e-4);
        var ended = p.sampleUserRulesAt(2, r, facts(false), new Env(), state);
        assertEquals(0, z(ended.parallelDeltas()[1]), 1e-4);
    }

    @Test
    void sneakStateAliasesUseTheSameOfficialNamesAsTheMovementSelector() {
        var p =
                program(
                        clip("sneaking", "\"Ear\":{\"rotation\":[0,0,10]}"),
                        clip("sneak", "\"Ear\":{\"rotation\":[0,0,20]}"));
        for (String semantic : List.of("sneak_idle", "sneak_move")) {
            var r =
                    rules(
                            """
                    {"overrides":[{"id":"sneak","source":{"kind":"authored_state",
                      "state":"%s","head_tracking":"none"}}]}
                    """
                                    .formatted(semantic));
            var frame =
                    p.sampleUserRulesAt(
                            0,
                            r,
                            facts(false),
                            new Env(),
                            new ParallelAnimationProgram.UserMotionState());
            assertEquals(
                    semantic.equals("sneak_idle") ? 10 : 20, z(frame.wholeModelDeltas()[1]), 1e-4);
        }
    }

    @Test
    void controlledEnvironmentReceivesTheActualPerClipTime() {
        var p = program(clip("pose", "\"Ear\":{\"rotation\":[0,0,\"q.anim_time * 60\"]}"));
        var r =
                rules(
                        """
                {"overrides":[{"id":"pose","source":{"id":"pose"}}]}
                """);
        var state = new ParallelAnimationProgram.UserMotionState();
        var environment = new Env();
        p.sampleUserRulesAt(10, r, facts(false), environment, state);
        var frame = p.sampleUserRulesAt(10.75, r, facts(false), environment, state);
        assertEquals(45, z(frame.wholeModelDeltas()[1]), 1e-4);
    }

    @Test
    void formInitializationTimelinesRunOnceWithoutPublishingEffects() {
        var init =
                BedrockAnimationParser.parse(
                        "parallel0",
                        JsonParser.parseString(
                                        """
                {"loop":true,"animation_length":1,
                 "timeline":{"0":["v.calls += 1; v.visible = 0; ysm.play_sound('private');"]},
                 "bones":{"Ear":{"scale":["v.visible","v.visible","v.visible"]}}}
                """)
                                .getAsJsonObject());
        var program = program(init);
        var state = new ParallelAnimationProgram.UserMotionState();
        var env = new Env();
        var frame = program.sampleUserRulesAt(.2, rules("{}"), facts(false), env, state);
        assertTrue(frame.hiddenBones().contains("Ear"));
        program.sampleUserRulesAt(.2, rules("{}"), facts(false), env, state);
        program.sampleUserRulesAt(.3, rules("{}"), facts(false), env, state);
        assertEquals(1, env.readVariable(ExpressionEngine.slot("v.calls")));
        assertEquals(0, env.externalCalls);
        program.sampleUserRulesAt(1.2, rules("{}"), facts(false), env, state);
        assertEquals(2, env.readVariable(ExpressionEngine.slot("v.calls")));
    }

    private record RootScaleSample(AuxiliaryBoneLayout layout,
                                   ParallelAnimationProgram.Frame frame) {
    }

    private static RootScaleSample rootScaleSample(float scale) {
        GeometryDocument geometry = rootScaleGeometry();
        AuxiliaryBoneLayout layout = AuxiliaryBoneLayout.create(geometry);
        AnimationClip rootScale = clip("parallel0",
                "\"Root\":{\"scale\":[" + scale + "," + scale + "," + scale + "]}");
        Map<String, AnimationClip> clips = new LinkedHashMap<>();
        clips.put(rootScale.name(), rootScale);
        ParallelAnimationProgram program = new ParallelAnimationProgram(
                geometry, clips, layout, 1.0F, 1.0F);
        ParallelAnimationProgram.Frame frame = program.sampleUserRulesAt(
                0.25, rules("{}"), facts(false), new Env(),
                new ParallelAnimationProgram.UserMotionState());
        return new RootScaleSample(layout, frame);
    }

    private static GeometryDocument rootScaleGeometry() {
        GeometryDocument geometry = new GeometryDocument();
        geometry.add(fixtureBone("Root", "", 0.0F, 0.0F, 0.0F));
        geometry.add(fixtureBone("MRightArm", "Root", 0.0F, 0.0F, 0.0F));
        geometry.add(fixtureBone("RightArm", "MRightArm", -0.3F, 1.5F, 0.0F));
        geometry.add(fixtureBone("RightForeArm", "RightArm", -0.3F, 1.05F, 0.0F));
        geometry.add(fixtureBone("MLeftArm", "Root", 0.0F, 0.0F, 0.0F));
        geometry.add(fixtureBone("LeftArm", "MLeftArm", 0.3F, 1.5F, 0.0F));
        geometry.add(fixtureBone("LeftForeArm", "LeftArm", 0.3F, 1.05F, 0.0F));
        geometry.linkHierarchy();
        return geometry;
    }

    private static GeometryDocument.Bone fixtureBone(
            String name, String parent, float x, float y, float z) {
        GeometryDocument.Bone bone = new GeometryDocument.Bone(name);
        bone.parentName(parent);
        bone.pivot(x, y, z);
        bone.faces().add(new GeometryDocument.Face(new Vector3f[]{
                new Vector3f(x - 0.05F, y - 0.05F, z),
                new Vector3f(x + 0.05F, y - 0.05F, z),
                new Vector3f(x + 0.05F, y + 0.05F, z),
                new Vector3f(x - 0.05F, y + 0.05F, z)
        }, new float[][]{{0, 0}, {1, 0}, {1, 1}, {0, 1}}, new Vector3f(0, 0, 1)));
        return bone;
    }

    private static Armature rotatedHumanoidArmature() {
        Map<String, Joint> joints = new LinkedHashMap<>();
        Joint root = joint(joints, "Root", HumanoidRig.ROOT, new OpenMatrix4f());
        Joint rightThigh = joint(joints, "Thigh_R", HumanoidRig.RIGHT_THIGH,
                new OpenMatrix4f());
        Joint rightLeg = joint(joints, "Leg_R", HumanoidRig.RIGHT_LEG,
                new OpenMatrix4f());
        Joint rightKnee = joint(joints, "Knee_R", HumanoidRig.RIGHT_KNEE,
                new OpenMatrix4f());
        Joint leftThigh = joint(joints, "Thigh_L", HumanoidRig.LEFT_THIGH,
                new OpenMatrix4f());
        Joint leftLeg = joint(joints, "Leg_L", HumanoidRig.LEFT_LEG,
                new OpenMatrix4f());
        Joint leftKnee = joint(joints, "Knee_L", HumanoidRig.LEFT_KNEE,
                new OpenMatrix4f());
        Joint torso = joint(joints, "Torso", HumanoidRig.TORSO,
                new OpenMatrix4f().translate(0.0F, 0.5F, 0.0F));
        Joint chest = joint(joints, "Chest", HumanoidRig.CHEST,
                new OpenMatrix4f().translate(0.0F, 0.5F, 0.0F));
        Joint head = joint(joints, "Head", HumanoidRig.HEAD,
                new OpenMatrix4f().translate(0.0F, 0.4F, 0.0F));
        Joint rightShoulder = joint(joints, "Shoulder_R", HumanoidRig.RIGHT_SHOULDER,
                new OpenMatrix4f().translate(-0.3F, 0.5F, 0.0F));
        Joint rightArm = joint(joints, "Arm_R", HumanoidRig.RIGHT_ARM,
                new OpenMatrix4f());
        Joint rightHand = joint(joints, "Hand_R", HumanoidRig.RIGHT_HAND,
                new OpenMatrix4f().translate(0.0F, -0.45F, 0.0F));
        Joint rightTool = joint(joints, "Tool_R", HumanoidRig.RIGHT_TOOL,
                new OpenMatrix4f().translate(0.0F, -0.2F, 0.0F));
        Joint rightElbow = joint(joints, "Elbow_R", HumanoidRig.RIGHT_ELBOW,
                new OpenMatrix4f().translate(0.0F, -0.45F, 0.0F));
        Joint leftShoulder = joint(joints, "Shoulder_L", HumanoidRig.LEFT_SHOULDER,
                new OpenMatrix4f().translate(0.3F, 0.5F, 0.0F));
        Joint leftArm = joint(joints, "Arm_L", HumanoidRig.LEFT_ARM,
                new OpenMatrix4f());
        Joint leftHand = joint(joints, "Hand_L", HumanoidRig.LEFT_HAND,
                new OpenMatrix4f().translate(0.0F, -0.45F, 0.0F));
        Joint leftTool = joint(joints, "Tool_L", HumanoidRig.LEFT_TOOL,
                new OpenMatrix4f().translate(0.0F, -0.2F, 0.0F));
        Joint leftElbow = joint(joints, "Elbow_L", HumanoidRig.LEFT_ELBOW,
                new OpenMatrix4f().translate(0.0F, -0.45F, 0.0F));

        root.addSubJoints(rightThigh, leftThigh, torso);
        rightThigh.addSubJoints(rightLeg, rightKnee);
        leftThigh.addSubJoints(leftLeg, leftKnee);
        torso.addSubJoints(chest);
        chest.addSubJoints(head, rightShoulder, leftShoulder);
        rightShoulder.addSubJoints(rightArm);
        rightArm.addSubJoints(rightHand, rightElbow);
        rightHand.addSubJoints(rightTool);
        leftShoulder.addSubJoints(leftArm);
        leftArm.addSubJoints(leftHand, leftElbow);
        leftHand.addSubJoints(leftTool);

        Armature armature = new Armature("root_scale_test",
                HumanoidRig.EPIC_JOINT_COUNT, root, joints);
        armature.bakeOriginMatrices();
        return armature;
    }

    private static Joint joint(Map<String, Joint> joints, String name, int id,
                               OpenMatrix4f local) {
        Joint joint = new Joint(name, id, local);
        joints.put(name, joint);
        return joint;
    }

    private static void assertSeam(AuxiliaryBoneLayout layout, OpenMatrix4f[] matrices,
                                   String parentName, String childName, Vector3f point) {
        var parent = layout.entryForBoneName(parentName);
        var child = layout.entryForBoneName(childName);
        assertNotNull(parent, parentName);
        assertNotNull(child, childName);
        Vec4f authored = new Vec4f(point.x(), point.y(), point.z(), 1.0F);
        Vec4f parentPoint = OpenMatrix4f.transform(
                matrices[parent.poseIndex()], authored, new Vec4f());
        Vec4f childPoint = OpenMatrix4f.transform(
                matrices[child.poseIndex()], authored, new Vec4f());
        assertEquals(parentPoint.x, childPoint.x, 0.0001F, childName);
        assertEquals(parentPoint.y, childPoint.y, 0.0001F, childName);
        assertEquals(parentPoint.z, childPoint.z, 0.0001F, childName);
    }

    private static void assertMatrixNear(OpenMatrix4f expected, OpenMatrix4f actual,
                                         float tolerance) {
        float[] left = matrixValues(expected);
        float[] right = matrixValues(actual);
        for (int index = 0; index < left.length; index++) {
            assertEquals(left[index], right[index], tolerance, "matrix element " + index);
        }
    }

    private static void assertFinite(OpenMatrix4f matrix) {
        for (float value : matrixValues(matrix)) assertTrue(Float.isFinite(value));
    }

    private static float linearDeterminant(OpenMatrix4f matrix) {
        return matrix.m00 * (matrix.m11 * matrix.m22 - matrix.m12 * matrix.m21)
                - matrix.m01 * (matrix.m10 * matrix.m22 - matrix.m12 * matrix.m20)
                + matrix.m02 * (matrix.m10 * matrix.m21 - matrix.m11 * matrix.m20);
    }

    private static float[] matrixValues(OpenMatrix4f m) {
        return new float[]{m.m00, m.m01, m.m02, m.m03,
                m.m10, m.m11, m.m12, m.m13,
                m.m20, m.m21, m.m22, m.m23,
                m.m30, m.m31, m.m32, m.m33};
    }

    private static float radians(float degrees) {
        return (float) Math.toRadians(degrees);
    }
}
