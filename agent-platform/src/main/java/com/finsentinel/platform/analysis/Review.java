package com.finsentinel.platform.analysis;

/** The reviewer's verdict on a draft report. */
public record Review(boolean approved, String comment) {
}
