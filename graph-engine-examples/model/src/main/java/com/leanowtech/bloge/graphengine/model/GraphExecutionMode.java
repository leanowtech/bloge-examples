package com.leanowtech.bloge.graphengine.model;

/**
 * Selects which BLOGE runtime family executes a published version.
 */
public enum GraphExecutionMode {
    GRAPH,
    STATE_MACHINE,
    SESSION
}
