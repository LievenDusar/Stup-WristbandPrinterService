package com.stup.wristbandprinter.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

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

    public static class Margins {
        private int topDots = 40;
        private int betweenLogoAndText = 150;
        private int betweenTextAndBarcode = 150;
        private int betweenBarcodeAndLogo = 60;

        public int getTopDots() { return topDots; }
        public void setTopDots(int topDots) { this.topDots = topDots; }

        public int getBetweenLogoAndText() { return betweenLogoAndText; }
        public void setBetweenLogoAndText(int betweenLogoAndText) { this.betweenLogoAndText = betweenLogoAndText; }

        public int getBetweenTextAndBarcode() { return betweenTextAndBarcode; }
        public void setBetweenTextAndBarcode(int betweenTextAndBarcode) { this.betweenTextAndBarcode = betweenTextAndBarcode; }

        public int getBetweenBarcodeAndLogo() { return betweenBarcodeAndLogo; }
        public void setBetweenBarcodeAndLogo(int betweenBarcodeAndLogo) { this.betweenBarcodeAndLogo = betweenBarcodeAndLogo; }
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
        private boolean showHumanReadable = true;

        public String getType() { return type; }
        public void setType(String type) { this.type = type; }

        public int getHeightDots() { return heightDots; }
        public void setHeightDots(int heightDots) { this.heightDots = heightDots; }

        public boolean isShowHumanReadable() { return showHumanReadable; }
        public void setShowHumanReadable(boolean showHumanReadable) { this.showHumanReadable = showHumanReadable; }
    }
}
