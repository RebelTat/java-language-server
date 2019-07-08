package org.javacs.debug;

/** Arguments for 'stepInTargets' request. */
public class StepInTargetsArguments {
    /** The stack frame for which to retrieve the possible stepIn targets. */
    int frameId;
}