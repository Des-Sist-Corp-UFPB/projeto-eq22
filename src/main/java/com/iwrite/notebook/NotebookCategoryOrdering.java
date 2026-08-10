package com.iwrite.notebook;

import com.iwrite.notebook.entity.NotebookCategory;

import java.text.Collator;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public final class NotebookCategoryOrdering {

    private static final Collator CATEGORY_COLLATOR = Collator.getInstance(Locale.forLanguageTag("pt-BR"));

    private NotebookCategoryOrdering() {
    }

    public static List<NotebookCategory> ordered(List<NotebookCategory> categories) {
        return categories.stream()
                .sorted(comparator())
                .toList();
    }

    private static Comparator<NotebookCategory> comparator() {
        return Comparator
                .comparing(NotebookCategoryOrdering::isOutroCategory)
                .thenComparing(NotebookCategory::getSortOrder)
                .thenComparing(NotebookCategory::getName, CATEGORY_COLLATOR)
                .thenComparing(NotebookCategory::getId);
    }

    private static boolean isOutroCategory(NotebookCategory category) {
        return category.getName().trim().equalsIgnoreCase("Outro");
    }
}
