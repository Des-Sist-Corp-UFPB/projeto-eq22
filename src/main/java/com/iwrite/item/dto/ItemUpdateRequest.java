package com.iwrite.item.dto;

import com.fasterxml.jackson.annotation.JsonSetter;

import java.util.UUID;

public class ItemUpdateRequest {

    private String name;
    private String type;
    private String description;
    private String origin;
    private UUID currentOwnerCharacterId;
    private boolean currentOwnerCharacterIdPresent;
    private String narrativeImportance;
    private String notes;

    public String name() {
        return name;
    }

    public String type() {
        return type;
    }

    public String description() {
        return description;
    }

    public String origin() {
        return origin;
    }

    public UUID currentOwnerCharacterId() {
        return currentOwnerCharacterId;
    }

    public boolean isCurrentOwnerCharacterIdPresent() {
        return currentOwnerCharacterIdPresent;
    }

    public String narrativeImportance() {
        return narrativeImportance;
    }

    public String notes() {
        return notes;
    }

    public void setName(String name) {
        this.name = name;
    }

    public void setType(String type) {
        this.type = type;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public void setOrigin(String origin) {
        this.origin = origin;
    }

    @JsonSetter("currentOwnerCharacterId")
    public void setCurrentOwnerCharacterId(UUID currentOwnerCharacterId) {
        this.currentOwnerCharacterId = currentOwnerCharacterId;
        this.currentOwnerCharacterIdPresent = true;
    }

    public void setNarrativeImportance(String narrativeImportance) {
        this.narrativeImportance = narrativeImportance;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }
}
