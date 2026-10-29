package net.okitsu.ysmepicfightcompat.animation;

/** Supplies a per-clip clock to a controlled, entity-free animation environment. */
interface ClipTimeEnvironment extends ExpressionEngine.Environment {
    void clipTime(double time);
}
