package com.stup.wristbandprinter.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;

@ConfigurationProperties(prefix = "wristband")
public class WristbandProperties {

    private int widthDots = 203;
    private int lengthDots = 2233;
    private int dpi = 203;
    private String logoPath = "classpath:images/stup-logo.png";
    private int logoSideMarginDots = 10;
    private Margins margins = new Margins();
    private Text text = new Text();
    private Barcode barcode = new Barcode();

    /**
     * Preview-only stock-color palette. Keys are integer color codes (1 = white is the default);
     * values are CSS hex strings (e.g. {@code "#FFFFFF"}). Applied via PreviewColorService.tint().
     */
    private Map<Integer, String> stockColors = new LinkedHashMap<>();

    public int getWidthDots() { return widthDots; }
    public void setWidthDots(int widthDots) { this.widthDots = widthDots; }

    public int getLengthDots() { return lengthDots; }
    public void setLengthDots(int lengthDots) { this.lengthDots = lengthDots; }

    public int getDpi() { return dpi; }
    public void setDpi(int dpi) { this.dpi = dpi; }

    public String getLogoPath() { return logoPath; }
    public void setLogoPath(String logoPath) { this.logoPath = logoPath; }

    public int getLogoSideMarginDots() { return logoSideMarginDots; }
    public void setLogoSideMarginDots(int logoSideMarginDots) { this.logoSideMarginDots = logoSideMarginDots; }

    public Margins getMargins() { return margins; }
    public void setMargins(Margins margins) { this.margins = margins; }

    public Text getText() { return text; }
    public void setText(Text text) { this.text = text; }

    public Barcode getBarcode() { return barcode; }
    public void setBarcode(Barcode barcode) { this.barcode = barcode; }

    public Map<Integer, String> getStockColors() { return stockColors; }
    public void setStockColors(Map<Integer, String> stockColors) { this.stockColors = stockColors; }

    /**
     * Vertical gaps in dots, named in band layout order: top logo → barcode → text → bottom logo.
     * Each value is the gap immediately before the named-second element.
     */
    public static class Margins {
        private int betweenLogoAndBarcode = 150; // top logo → barcode
        private int betweenBarcodeAndText = 150; // barcode → text
        private int betweenTextAndLogo = 60;     // text → bottom logo

        public int getBetweenLogoAndBarcode() { return betweenLogoAndBarcode; }
        public void setBetweenLogoAndBarcode(int betweenLogoAndBarcode) { this.betweenLogoAndBarcode = betweenLogoAndBarcode; }

        public int getBetweenBarcodeAndText() { return betweenBarcodeAndText; }
        public void setBetweenBarcodeAndText(int betweenBarcodeAndText) { this.betweenBarcodeAndText = betweenBarcodeAndText; }

        public int getBetweenTextAndLogo() { return betweenTextAndLogo; }
        public void setBetweenTextAndLogo(int betweenTextAndLogo) { this.betweenTextAndLogo = betweenTextAndLogo; }
    }

    public static class Text {
        private int fontSizeEvent = 20;
        private int fontSizeName = 28;
        private int fontSizeAssociation = 20;

        public int getFontSizeEvent() { return fontSizeEvent; }
        public void setFontSizeEvent(int fontSizeEvent) { this.fontSizeEvent = fontSizeEvent; }

        public int getFontSizeName() { return fontSizeName; }
        public void setFontSizeName(int fontSizeName) { this.fontSizeName = fontSizeName; }

        public int getFontSizeAssociation() { return fontSizeAssociation; }
        public void setFontSizeAssociation(int fontSizeAssociation) { this.fontSizeAssociation = fontSizeAssociation; }
    }

    public static class Barcode {
        private String type = "CODE128";
        private int heightDots = 100;
        // Narrow-bar (module) width in dots, emitted via ^BY. ZPL default is 2. For a 90°-rotated
        // barcode this is the dimension along the band's long side: larger = longer, easier to scan.
        private int moduleWidthDots = 2;
        private boolean showHumanReadable = true;

        public String getType() { return type; }
        public void setType(String type) { this.type = type; }

        public int getHeightDots() { return heightDots; }
        public void setHeightDots(int heightDots) { this.heightDots = heightDots; }

        public int getModuleWidthDots() { return moduleWidthDots; }
        public void setModuleWidthDots(int moduleWidthDots) { this.moduleWidthDots = moduleWidthDots; }

        public boolean isShowHumanReadable() { return showHumanReadable; }
        public void setShowHumanReadable(boolean showHumanReadable) { this.showHumanReadable = showHumanReadable; }
    }
}
