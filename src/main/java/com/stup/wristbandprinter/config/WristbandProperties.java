package com.stup.wristbandprinter.config;

import com.stup.wristbandprinter.domain.CodeSymbology;
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
    private Permit permit = new Permit();
    private FreeText freeText = new FreeText();

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

    public Permit getPermit() { return permit; }
    public void setPermit(Permit permit) { this.permit = permit; }

    public FreeText getFreeText() { return freeText; }
    public void setFreeText(FreeText freeText) { this.freeText = freeText; }

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
        private int fontSizeClub = 20;

        public int getFontSizeEvent() { return fontSizeEvent; }
        public void setFontSizeEvent(int fontSizeEvent) { this.fontSizeEvent = fontSizeEvent; }

        public int getFontSizeName() { return fontSizeName; }
        public void setFontSizeName(int fontSizeName) { this.fontSizeName = fontSizeName; }

        public int getFontSizeClub() { return fontSizeClub; }
        public void setFontSizeClub(int fontSizeClub) { this.fontSizeClub = fontSizeClub; }
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

    /** Configuration for the permit wristband layout (wristband.permit.*). */
    public static class Permit {

        private PermitMargins margins = new PermitMargins();
        private PermitText text = new PermitText();
        private PermitCode code = new PermitCode();

        public PermitMargins getMargins() { return margins; }
        public void setMargins(PermitMargins margins) { this.margins = margins; }

        public PermitText getText() { return text; }
        public void setText(PermitText text) { this.text = text; }

        public PermitCode getCode() { return code; }
        public void setCode(PermitCode code) { this.code = code; }

        public static class PermitMargins {
            /** Uniform gap in dots between every adjacent block pair. */
            private int betweenBlocks = 60;
            /**
             * Gap in dots between the two stacked lines of block 2 ("Toelating …" and "aan …"),
             * across the band width. Kept small (like the crew band's inter-line gap) so the two
             * lines read as one centered text block.
             */
            private int interLineGap = 12;

            public int getBetweenBlocks() { return betweenBlocks; }
            public void setBetweenBlocks(int betweenBlocks) { this.betweenBlocks = betweenBlocks; }

            public int getInterLineGap() { return interLineGap; }
            public void setInterLineGap(int interLineGap) { this.interLineGap = interLineGap; }
        }

        public static class PermitText {
            private int fontSizePermitLabel = 66;
            private int fontSizeClub = 42;
            private int fontSizeEventName   = 52;
            /** Number of '.' characters to print when no clubName is supplied. */
            private int dotCount = 30;

            public int getFontSizePermitLabel() { return fontSizePermitLabel; }
            public void setFontSizePermitLabel(int f) { this.fontSizePermitLabel = f; }

            public int getFontSizeClub() { return fontSizeClub; }
            public void setFontSizeClub(int f) { this.fontSizeClub = f; }

            public int getFontSizeEventName() { return fontSizeEventName; }
            public void setFontSizeEventName(int f) { this.fontSizeEventName = f; }

            public int getDotCount() { return dotCount; }
            public void setDotCount(int dotCount) { this.dotCount = dotCount; }
        }

        public static class PermitCode {
            private CodeSymbology defaultSymbology = CodeSymbology.CODE128;
            private int heightDots = 200;
            private int moduleWidthDots = 2;
            private boolean showHumanReadable = false;

            public CodeSymbology getDefaultSymbology() { return defaultSymbology; }
            public void setDefaultSymbology(CodeSymbology defaultSymbology) { this.defaultSymbology = defaultSymbology; }

            public int getHeightDots() { return heightDots; }
            public void setHeightDots(int heightDots) { this.heightDots = heightDots; }

            public int getModuleWidthDots() { return moduleWidthDots; }
            public void setModuleWidthDots(int moduleWidthDots) { this.moduleWidthDots = moduleWidthDots; }

            public boolean isShowHumanReadable() { return showHumanReadable; }
            public void setShowHumanReadable(boolean showHumanReadable) { this.showHumanReadable = showHumanReadable; }
        }
    }

    /** Configuration for the free-text wristband layout (wristband.free-text.*). */
    public static class FreeText {
        private int fontSize = 66;
        /** Gap in dots between the logo and the text block, applied symmetrically on both sides. */
        private int betweenLogoAndText = 150;

        public int getFontSize() { return fontSize; }
        public void setFontSize(int fontSize) { this.fontSize = fontSize; }

        public int getBetweenLogoAndText() { return betweenLogoAndText; }
        public void setBetweenLogoAndText(int betweenLogoAndText) { this.betweenLogoAndText = betweenLogoAndText; }
    }
}
