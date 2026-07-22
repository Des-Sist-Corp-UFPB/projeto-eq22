package com.iwrite.item.controller;

import com.iwrite.item.dto.ItemRequest;
import com.iwrite.item.dto.ItemResponse;
import com.iwrite.item.dto.ItemUpdateRequest;
import com.iwrite.item.service.ItemService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api")
public class ItemController {

    private final ItemService itemService;

    public ItemController(ItemService itemService) {
        this.itemService = itemService;
    }

    @GetMapping("/books/{bookId}/items")
    public List<ItemResponse> findByBook(@PathVariable UUID bookId) {
        return itemService.findByBook(bookId);
    }

    @PostMapping("/books/{bookId}/items")
    @ResponseStatus(HttpStatus.CREATED)
    public ItemResponse create(@PathVariable UUID bookId, @Valid @RequestBody ItemRequest request) {
        return itemService.create(bookId, request);
    }

    @GetMapping("/items/{itemId}")
    public ItemResponse findById(@PathVariable UUID itemId) {
        return itemService.findById(itemId);
    }

    @PatchMapping("/items/{itemId}")
    public ItemResponse update(@PathVariable UUID itemId, @Valid @RequestBody ItemUpdateRequest request) {
        return itemService.update(itemId, request);
    }

    @DeleteMapping("/items/{itemId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID itemId) {
        itemService.delete(itemId);
    }
}
