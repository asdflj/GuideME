package guideme.color;

public record ConstantColor(int lightModeColor, int darkModeColor) implements ColorValue {
    public static ConstantColor WHITE = new ConstantColor(-1, -1);

    public static ConstantColor BLACK = new ConstantColor(0xFF000000, 0xFF000000);

    public static ConstantColor TRANSPARENT = new ConstantColor(0, 0);

    public ConstantColor(int color) {
        this(color, color);
    }

    @Override
    public int resolve(LightDarkMode lightDarkMode) {
        return lightDarkMode == LightDarkMode.LIGHT_MODE ? lightModeColor : darkModeColor;
    }
}
