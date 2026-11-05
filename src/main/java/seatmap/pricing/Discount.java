package seatmap.pricing;

/** A reduction applied to an order, with the label shown on the quote. */
public record Discount(String label, Money amount) {
}
