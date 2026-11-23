/*
 * STEP 2 - SPLIT MULTICHANNEL TIFFS INTO INDIVIDUAL CHANNELS
 *
 * Run after Step 1 has exported the multichannel TIFF files.
 * This script ONLY splits and organizes images. It does not analyze them.
 *
 * Output for each channel:
 *   original/          untouched split channel at its original bit depth
 *   ready_for_analysis/  working copy at its original bit depth
 *
 * After this script finishes, open the ready_for_analysis images in Fiji,
 * use your usual lasso + Process > Math > Subtract workflow, and SAVE the
 * corrected image. Step 3 reads only ready_for_analysis.
 */
import ij.IJ
import ij.ImagePlus
import ij.gui.Roi
import ij.io.RoiEncoder
import ij.process.ByteProcessor
import ij.process.ColorProcessor
import ij.process.ImageProcessor

// ---------------- SETTINGS ----------------
OUTPUT_FOLDER = "E:/VA Chronic stress GluN1 + psd95 staining/PFC analysis"

// IMPORTANT: This must exactly match the folder where Step 1 exported TIFFs.
EXPORT_SUBFOLDER = "Multichannel Tiffs"

ORIGINAL_SUBFOLDER = "original"
READY_SUBFOLDER = "ready_for_analysis"

// Only identify which exported channels to split and what to call them.
// Analysis settings (pixel size, rolling-ball radius, thresholds, etc.) belong
// in Step 3, not here.
CHANNELS = [
    [ number: 1, name: "GluN1" ],
    [ number: 2, name: "PSD95" ]
]

OUTLINE_NAME = "QuPath annotation"

// ---------------- MAIN PROGRAM ----------------
exportFolder = new File(OUTPUT_FOLDER, EXPORT_SUBFOLDER)
if (!exportFolder.exists()) {
    throw new IllegalArgumentException("Could not find exported TIFF folder: " + exportFolder)
}
def mergedTiffs = listTiffs(exportFolder)
println "\n===== STEP 2: splitting ${mergedTiffs.size()} multichannel TIFF(s) ====="
if (mergedTiffs.isEmpty()) {
    println "No TIFF files found. Check OUTPUT_FOLDER and EXPORT_SUBFOLDER."
}
for (tiff in mergedTiffs) {
    splitOneTiff(tiff)
}
println "\nSTEP 2 DONE. Split images are in: " + OUTPUT_FOLDER


// ---------------- SPLIT ONE TIFF ----------------
def splitOneTiff(File tiffFile) {
    def imageName = stripTiffExtension(tiffFile.getName())
    ImagePlus image = IJ.openImage(tiffFile.getAbsolutePath())
    if (image == null) {
        println "ERROR: could not open " + tiffFile
        return
    }

    // Preserve the QuPath annotation boundary, if present.
    Roi outline = findAnnotationOutline(image, imageName)

    boolean isColourPhoto = (image.getBitDepth() == 24)
    int channelCount = isColourPhoto ? 3 : image.getNChannels()

    for (channel in CHANNELS) {
        if (channel.number > channelCount) {
            println "WARNING: " + imageName + " has only " + channelCount +
                    " channel(s); skipping " + channel.name
            continue
        }
        ImageProcessor originalPixels
        String rangeText

       if (isColourPhoto) {
    int total = image.getWidth() * image.getHeight()
    byte[] r = new byte[total]
    byte[] g = new byte[total]
    byte[] b = new byte[total]
    ((ColorProcessor) image.getProcessor()).getRGB(r, g, b)

    workingPixels = new ByteProcessor(image.getWidth(), image.getHeight(),
            [r, g, b][channel.number - 1])

    originalPixels = workingPixels.duplicate()

} else {
    image.setPosition(channel.number, 1, 1)

    ImageProcessor pixels = image.getStack()
            .getProcessor(image.getStackIndex(channel.number, 1, 1)).duplicate()

    originalPixels = pixels.duplicate()
    workingPixels = pixels.duplicate()
}
        // Save untouched original-depth split channel.
        ImagePlus originalImage = new ImagePlus(imageName + suffix + "_original", originalPixels)
        if (outline != null) originalImage.setRoi(outline)
        File originalFile = new File(new File(channelFolder(channel), ORIGINAL_SUBFOLDER),
                imageName + suffix + "_original.tif")
        saveTiff(originalImage, originalFile)
        originalImage.close()

      // Save working copy at its original bit depth for manual correction.
        ImagePlus readyImage = new ImagePlus(imageName + suffix, workingPixels)
        if (outline != null) readyImage.setRoi(outline)
        
        File readyFolder = new File(channelFolder(channel), READY_SUBFOLDER)
        File readyFile = new File(readyFolder, imageName + suffix + ".tif")
        if (saveTiff(readyImage, readyFile)) {
            // Save the annotation boundary separately so Step 3 can restore it
            // even if the active lasso selection changes during manual editing.
            if (outline != null) {
                File roiFile = new File(readyFolder, imageName + suffix + "_annotation.roi")
                new RoiEncoder(roiFile.getAbsolutePath()).write(outline)
            }
           println "   split: " + readyFile.getName() +
        " (" + readyImage.getBitDepth() + "-bit)"
        }
        readyImage.close()
    }
    image.close()
}


// ---------------- FIND THE QUPATH OUTLINE ----------------
def findAnnotationOutline(ImagePlus image, String imageName) {
    def overlay = image.getOverlay()
    if (overlay != null) {
        for (int i = 0; i < overlay.size(); i++) {
            Roi shape = overlay.get(i)
            if (OUTLINE_NAME.equals(shape.getName())) return shape
        }
    }
    println "WARNING: " + imageName + " has no '" + OUTLINE_NAME +
            "' outline. Step 3 will measure the full image unless you add an ROI."
    return null
}

// ---------------- SHARED HELPERS ----------------
def channelFolder(Map channel) {
    return new File(OUTPUT_FOLDER, channel.name)
}
def channelSuffix(Map channel) {
    return "_" + channel.name.toLowerCase()
}
def listTiffs(File folder) {
    def files = folder.listFiles()
    if (files == null) return []
    return files.findAll { it.isFile() &&
            (it.getName().toLowerCase().endsWith(".tif") ||
             it.getName().toLowerCase().endsWith(".tiff")) }.sort { it.getName() }
}
def stripTiffExtension(String name) {
    return name.replaceAll(/(?i)\.tiff?$/, "")
}
def saveTiff(ImagePlus image, File file) {
    file.getParentFile().mkdirs()
    IJ.saveAsTiff(image, file.getAbsolutePath())
    if (!file.exists()) {
        println "ERROR: could not save " + file
        return false
    }
    return true
}
