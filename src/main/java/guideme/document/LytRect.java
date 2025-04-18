package guideme.document;

import org.joml.Matrix4f;
import org.joml.Vector2i;
import org.joml.Vector3f;

public record LytRect(int x, int y, int width, int height) {

    private static final LytRect EMPTY = new LytRect(0, 0, 0, 0);

    public static LytRect empty() {
        return EMPTY;
    }

    public int right() {
        return x + width;
    }

    public int bottom() {
        return y + height;
    }

    public boolean isEmpty() {
        return width == 0 || height == 0;
    }

    public LytRect shrink(int left, int top, int right, int bottom) {
        return new LytRect(
                x + left,
                y + top,
                Math.max(width - left - right, 0),
                Math.max(height - top - bottom, 0));
    }

    public LytRect expand(int amount) {
        return expand(amount, amount, amount, amount);
    }

    public LytRect expand(int left, int top, int right, int bottom) {
        return new LytRect(
                x - left,
                y - top,
                Math.max(width + left + right, 0),
                Math.max(height + top + bottom, 0));
    }

    public LytRect withWidth(int width) {
        return new LytRect(x, y, width, height);
    }

    public LytRect withHeight(int height) {
        return new LytRect(x, y, width, height);
    }

    public LytRect move(int x, int y) {
        return new LytRect(this.x + x, this.y + y, width, height);
    }

    public LytRect centerIn(LytRect other) {
        var centerXOther = other.x + other.width / 2;
        var centerYOther = other.y + other.height / 2;
        return new LytRect(centerXOther - width / 2, centerYOther - height / 2, width, height);
    }

    public LytRect centerHorizontallyIn(LytRect other) {
        var centerXOther = other.x + other.width / 2;
        return new LytRect(centerXOther - width / 2, y, width, height);
    }

    public LytRect centerVerticallyIn(LytRect other) {
        var centerYOther = other.y + other.height / 2;
        return new LytRect(x, centerYOther - height / 2, width, height);
    }

    public static LytRect union(LytRect a, LytRect b) {
        if (a.isEmpty()) {
            return b;
        } else if (b.isEmpty()) {
            return a;
        }

        int x = Math.min(a.x, b.x);
        int y = Math.min(a.y, b.y);
        int right = Math.max(a.right(), b.right());
        int bottom = Math.max(a.bottom(), b.bottom());

        return new LytRect(
                x, y,
                right - x,
                bottom - y);
    }

    public boolean contains(LytPoint point) {
        return point.x() >= this.x && point.x() < right() &&
                point.y() >= this.y && point.y() < bottom();
    }

    public boolean contains(int x, int y) {
        return x >= this.x && x < right() &&
                y >= this.y && y < bottom();
    }

    public boolean intersects(LytRect other) {
        return right() > other.x() && x < other.right() && bottom() > other.y && y < other.bottom();
    }

    public LytRect withX(int x) {
        return new LytRect(x, y, width, height);
    }

    public LytRect withY(int y) {
        return new LytRect(x, y, width, height);
    }

    public LytRect transform(Matrix4f pose) {
        var tmp = new Vector3f();
        pose.transformPosition(x, y, 0, tmp);

        var left = tmp.x;
        var top = tmp.y;

        pose.transformPosition(x + width, y + height, 0, tmp);

        var width = Math.ceil(tmp.x - left);
        var height = Math.ceil(tmp.y - top);
        return new LytRect(
                (int) Math.floor(left),
                (int) Math.floor(top),
                (int) Math.ceil(width),
                (int) Math.ceil(height));
    }

    public LytSize size() {
        return new LytSize(width, height);
    }

    public Vector2i point() {
        return new Vector2i(x, y);
    }
}
