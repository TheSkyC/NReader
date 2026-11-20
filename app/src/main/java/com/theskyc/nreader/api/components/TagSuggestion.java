package com.theskyc.nreader.api.components;

/** A displayed suggestion that retains its canonical site tag. */
public final class TagSuggestion {
    private final Tag tag;
    private final String displayName;

    public TagSuggestion(Tag tag, String displayName) {
        this.tag = tag;
        this.displayName = displayName;
    }

    public Tag getTag() {
        return tag;
    }

    public String getDisplayName() {
        return displayName;
    }

    @Override
    public String toString() {
        return displayName.equals(tag.getName()) ? displayName : displayName + " (" + tag.getName() + ")";
    }
}
