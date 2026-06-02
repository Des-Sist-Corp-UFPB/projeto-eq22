package com.iwrite.scene.service;

import com.iwrite.scene.entity.Scene;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class ScenePlanningCompletenessService {

    public boolean isComplete(Scene scene) {
        return planningGaps(scene).isEmpty();
    }

    public List<String> planningGaps(Scene scene) {
        List<String> gaps = new ArrayList<>();
        if (scene.getPovCharacter() == null) {
            gaps.add("POV");
        }
        if (!hasText(scene.getGoal())) {
            gaps.add("Objetivo");
        }
        if (!hasText(scene.getConflict())) {
            gaps.add("Conflito");
        }
        if (!hasText(scene.getOutcome())) {
            gaps.add("Resultado");
        }
        return gaps;
    }

    public String formatMissingPlanningFields(Scene scene) {
        return String.join(", ", planningGaps(scene));
    }

    public boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
