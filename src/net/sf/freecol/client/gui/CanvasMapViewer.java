package net.sf.freecol.client.gui;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.font.TextLayout;
import java.awt.geom.Rectangle2D;
import java.util.logging.Logger;

import javax.swing.JComponent;

import net.sf.freecol.FreeCol;
import net.sf.freecol.client.FreeColClient;
import net.sf.freecol.common.debug.FreeColDebugger;

public class CanvasMapViewer extends JComponent {
    
    private static final Logger logger = Logger.getLogger(CanvasMapViewer.class.getName());

    private final FreeColClient freeColClient;
    private final MapViewer mapViewer;
    private boolean partialPaint = false;
    private boolean dirty = false;
    
    CanvasMapViewer(FreeColClient freeColClient, MapViewer mapViewer) {
        this.freeColClient = freeColClient;
        this.mapViewer = mapViewer;
        setLayout(null);
    }
    
    
    /**
     * Change the displayed map size.
     *
     * @param size The new map size.
     */
    public void changeSize(Dimension size) {
        setSize(size);
        mapViewer.changeSize(size);
        this.dirty = true;
    }
    
    protected void paintAnimationImmediately() {
        if (!isMapAvailable()) {
            return;
        }
        
        final Graphics2D g2d = (Graphics2D) getGraphics();
        final Dimension size = getSize();
        g2d.setClip(0, 0, size.width, size.height);
        this.mapViewer.displayMapAnimationFrame(g2d, size);
        g2d.dispose();
    }
    
    @Override
    protected void paintComponent(Graphics g) {
        final long startTime = System.currentTimeMillis();
        super.paintComponent(g);
        
        final Dimension size = getSize();
        final Graphics2D g2d = (Graphics2D) g;

        if (isMapAvailable()) {
            if (partialPaint && !dirty) {
                this.mapViewer.displayMapAnimationFrame(g2d, size);
            } else {
                this.mapViewer.displayMap(g2d, size);
            }
            
            partialPaint = false;
            dirty = false;
        } else if (this.freeColClient.isMapEditor()) {
            paintBlackBackground(g2d, size);
        } else { /* main menu */
            paintMainMenuBackground(g2d, size);
        }
        
        if (FreeColDebugger.isInDebugMode()) {
            g.setColor(Color.white);
            final long renderTime = System.currentTimeMillis() - startTime;
            g.drawString(renderTime + "ms ==> " + (1000 / (renderTime+1)) + "fps", 50, 50);
        }
    }


    private boolean isMapAvailable() {
        return this.freeColClient != null
                && this.freeColClient.getGame() != null
                && this.freeColClient.getGame().getMap() != null;
    }

    private void paintMainMenuBackground(Graphics2D g2d, Dimension size) {
        // Get the background without scaling, to avoid wasting
        // memory needlessly keeping an unbounded number of rescaled
        // versions of the largest image in FreeCol, forever.
        final Image bgImage = ImageLibrary.getCanvasBackgroundImage();
        if (bgImage != null) {
            // Draw background image with scaling.
            g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g2d.drawImage(bgImage, 0, 0, size.width, size.height, this);
            String versionStr = "v. " + FreeCol.getVersion();
            Font oldFont = g2d.getFont();
            Color oldColor = g2d.getColor();
            Font newFont = oldFont.deriveFont(Font.BOLD);
            TextLayout layout = new TextLayout(versionStr, newFont,
                g2d.getFontRenderContext());
            Rectangle2D bounds = layout.getBounds();
            float x = size.width - (float) bounds.getWidth() - 5;
            float y = size.height - (float) bounds.getHeight();
            g2d.setColor(Color.white);
            layout.draw(g2d, x, y);
            g2d.setFont(oldFont);
            g2d.setColor(oldColor);
        } else {
            g2d.setColor(Color.WHITE);
            g2d.fillRect(0, 0, size.width, size.height);
            logger.warning("Unable to load the canvas background");
        }
    }

    private void paintBlackBackground(Graphics2D g2d, Dimension size) {
        g2d.setColor(Color.BLACK);
        g2d.fillRect(0, 0, size.width, size.height);
    }


    public void setPartialPaint(boolean partialPaint) {
        this.partialPaint = partialPaint;
    }
}