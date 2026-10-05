package com.scoopy;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Scope;
import org.springframework.context.annotation.ScopedProxyMode;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.io.Serializable;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

@SpringBootApplication
public class StoreApp {
    public static void main(String[] args) { SpringApplication.run(StoreApp.class, args); }
}

/** A product in the catalogue. Price is in rupees. */
record Product(int id, String name, String category, int price, int mrp, String emoji, String desc, double rating) {
    int off() { return (int) Math.round((mrp - price) * 100.0 / mrp); }
}

record Line(Product product, int qty) {
    int subtotal() { return product.price() * qty; }
}

/** In-memory catalogue. Swap for a JPA repository when you add a database. */
@Component
class Catalogue {
    final List<Product> all = List.of(
        new Product(1, "Belgian Chocolate Tub", "Tubs", 349, 429, "🍫", "Dark cocoa, 500 ml, slow churned.", 4.7),
        new Product(2, "Strawberry Swirl Tub", "Tubs", 299, 359, "🍓", "Real fruit ribbons, 500 ml.", 4.5),
        new Product(3, "Madagascar Vanilla Tub", "Tubs", 279, 329, "🍦", "Classic vanilla bean, 500 ml.", 4.6),
        new Product(4, "Pistachio Dream Tub", "Tubs", 399, 499, "🥜", "Roasted pistachio, 500 ml.", 4.8),
        new Product(5, "Choco Crunch Cone", "Cones", 69, 80, "🍦", "Waffle cone, choco shell.", 4.3),
        new Product(6, "Butterscotch Cone", "Cones", 59, 70, "🍨", "Caramel and crunchy praline.", 4.4),
        new Product(7, "Mango Alphonso Cup", "Cups", 89, 110, "🥭", "Alphonso mango, 120 ml cup.", 4.6),
        new Product(8, "Black Currant Cup", "Cups", 79, 95, "🍇", "Tangy and creamy, 120 ml cup.", 4.2),
        new Product(9, "Brownie Fudge Sundae", "Sundaes", 189, 229, "🍨", "Brownie, hot fudge, nuts.", 4.9),
        new Product(10, "Banana Split", "Sundaes", 219, 259, "🍌", "Three scoops, banana, sauces.", 4.5),
        new Product(11, "Mango Kulfi Stick", "Sticks", 45, 55, "🍡", "Thick, slow-cooked kulfi.", 4.7),
        new Product(12, "Orange Ice Pop", "Sticks", 25, 30, "🍊", "Zesty, dairy-free ice pop.", 4.0));

    List<String> categories() { return all.stream().map(Product::category).distinct().toList(); }
    Optional<Product> find(int id) { return all.stream().filter(p -> p.id() == id).findFirst(); }
}

/** One cart per browser session. */
@Component
@Scope(value = "session", proxyMode = ScopedProxyMode.TARGET_CLASS)
class Cart implements Serializable {
    private final Map<Integer, Integer> items = new LinkedHashMap<>();
    private final transient Catalogue catalogue;
    Cart(Catalogue catalogue) { this.catalogue = catalogue; }

    void change(int id, int delta) {
        int q = items.getOrDefault(id, 0) + delta;
        if (q <= 0) items.remove(id); else items.put(id, Math.min(q, 20));
    }
    List<Line> lines() {
        return items.entrySet().stream()
            .map(e -> catalogue.find(e.getKey()).map(p -> new Line(p, e.getValue())).orElse(null))
            .filter(Objects::nonNull).collect(Collectors.toList());
    }
    int count() { return items.values().stream().mapToInt(Integer::intValue).sum(); }
    int subtotal() { return lines().stream().mapToInt(Line::subtotal).sum(); }
    int delivery() { return items.isEmpty() || subtotal() >= 299 ? 0 : 29; }
    int total() { return subtotal() + delivery(); }
    void clear() { items.clear(); }
}

@Controller
class StoreController {
    private final Catalogue catalogue;
    private final Cart cart;
    private final AtomicInteger orderSeq = new AtomicInteger(1000);

    StoreController(Catalogue catalogue, Cart cart) { this.catalogue = catalogue; this.cart = cart; }

    @ModelAttribute
    void common(Model m) { m.addAttribute("cart", cart); }

    @GetMapping("/")
    String home(@RequestParam(defaultValue = "") String q, @RequestParam(defaultValue = "") String cat,
                @RequestParam(defaultValue = "popular") String sort, Model m) {
        List<Product> list = catalogue.all.stream()
            .filter(p -> cat.isBlank() || p.category().equals(cat))
            .filter(p -> q.isBlank() || p.name().toLowerCase().contains(q.toLowerCase()))
            .sorted(switch (sort) {
                case "low" -> Comparator.comparingInt(Product::price);
                case "high" -> Comparator.comparingInt(Product::price).reversed();
                default -> Comparator.comparingDouble(Product::rating).reversed();
            }).toList();
        m.addAttribute("products", list);
        m.addAttribute("categories", catalogue.categories());
        m.addAttribute("q", q); m.addAttribute("cat", cat); m.addAttribute("sort", sort);
        return "index";
    }

    @PostMapping("/cart/change/{id}")
    String change(@PathVariable int id, @RequestParam int delta, @RequestHeader(value = "Referer", defaultValue = "/") String back) {
        catalogue.find(id).ifPresent(p -> cart.change(id, delta));
        return "redirect:" + (back.contains("/cart") ? "/cart" : "/#p" + id);
    }

    /** JSON endpoint called by app.js so the page updates without a reload. */
    @PostMapping("/api/cart/change/{id}")
    @ResponseBody
    Map<String, Object> apiChange(@PathVariable int id, @RequestParam int delta) {
        catalogue.find(id).ifPresent(p -> cart.change(id, delta));
        int qty = cart.lines().stream().filter(l -> l.product().id() == id).mapToInt(Line::qty).sum();
        int price = catalogue.find(id).map(Product::price).orElse(0);
        return Map.of("count", cart.count(), "qty", qty, "lineTotal", qty * price,
                      "subtotal", cart.subtotal(), "delivery", cart.delivery(), "total", cart.total());
    }

    @GetMapping("/cart")
    String cartPage() { return "cart"; }

    @PostMapping("/checkout")
    String checkout(@RequestParam String name, @RequestParam String phone, @RequestParam String address, Model m) {
        if (cart.count() == 0) return "redirect:/";
        m.addAttribute("orderId", "SC" + orderSeq.incrementAndGet());
        m.addAttribute("name", name);
        m.addAttribute("total", cart.total());
        cart.clear();
        return "success";
    }
}
