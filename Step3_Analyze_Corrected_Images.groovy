/*
 * STEP 3 - ANALYZE ALL MANUALLY CORRECTED CHANNEL IMAGES
 *
 * Run only after Step 2 has split the TIFFs and you have finished manually
 * correcting images in each channel's ready_for_analysis folder.
 *
 * This script reads ONLY ready_for_analysis. It does not split images and
 * does not repeat your manual Process > Math > Subtract corrections.
 *
 * Outputs: bgsub/, thresh/, and one measurements CSV per channel.
 */
import ij.IJ
import ij.ImagePlus
import ij.gui.Roi
import ij.io.RoiEncoder
import ij.measure.Calibration
import ij.measure.Measurements
import ij.plugin.filter.BackgroundSubtracter
import ij.process.ByteProcessor
import ij.process.ColorProcessor
import ij.process.ImageProcessor
import ij.process.ImageStatistics
import ij.io.RoiDecoder

// ---------------- SETTINGS ----------------
OUTPUT_FOLDER = "E:/VA Chronic stress GluN1 + psd95 staining/PFC analysis"
PIXEL_SIZE_UM = 0.137

// Keep true to measure inside the QuPath annotation boundary saved by Step 2.
MEASURE_INSIDE_ANNOTATION_ONLY = true

READY_SUBFOLDER = "ready_for_analysis"
BGSUB_SUBFOLDER = "bgsub"
THRESH_SUBFOLDER = "thresh"

CHANNELS = [

    [ number            : 1,
      name              : "GluN1",
      rollingBallRadius : 150,
      slidingParaboloid : true,
      lightBackground   : false,
      thresholdMin      : 24,
      thresholdMax      : 255,
      eightBitRange     : null ],

    // !!! The PSD95 numbers below are PLACEHOLDERS copied from GluN1, because the
    // !!! old scripts only contained analysis settings for GluN1.
    // !!! Replace rollingBallRadius and thresholdMin with your real PSD95 values.
    [ number            : 2,
      name              : "PSD95",
      rollingBallRadius : 150,
      slidingParaboloid : true,
      lightBackground   : false,
      thresholdMin      : 24,
      thresholdMax      : 255,
      eightBitRange     : null ],

]

// ---------------- CSV COLUMNS ----------------
CSV_HEADER = [
    "Image",                      // slide name + annotation name
    "Channel",                    // channel name from CHANNELS
    "Region_Area_um2",            // annotation area measured
    "Signal_Area_um2",            // area of the pixels inside the threshold
    "Percent_Area",               // Signal_Area / Region_Area x 100   ("%Area" in ImageJ)
    "Mean_of_Signal",             // average brightness (0-255) of the thresholded pixels
    "IntDen_of_Signal",           // Signal_Area_um2 x Mean_of_Signal  ("IntDen" in ImageJ)
    "RawIntDen_of_Signal",        // sum of the brightness of all thresholded pixels
    "Mean_of_Whole_Region",       // average brightness of the whole region (after bgsub)
    "Min_of_Whole_Region",
    "Max_of_Whole_Region",
    "Pixel_Size_um",              // the settings that were used, so that every
    "Rolling_Ball_Radius",        // row records how it was produced
    "Threshold_Min",
    "Threshold_Max",
    "EightBit_Range_Used"
]
// ---------------- MAIN PROGRAM ----------------
checkSettings()
println "\n===== STEP 3: analyzing manually corrected images ====="

for (channel in CHANNELS) {
    File readyFolder = new File(channelFolder(channel), READY_SUBFOLDER)
    def files = listTiffs(readyFolder)
    println "\n" + channel.name + ": found " + files.size() + " image(s)"
    def rows = []

    for (file in files) {
        // Ignore any other TIFFs that may have been placed in this folder.
        if (file.getName().toLowerCase().endsWith("_original.tif")) continue
        String base = stripTiffExtension(file.getName())
        String imageName = stripEnding(base, channelSuffix(channel))
        def row = stepB_analyzeImage(file, channel, imageName)
        if (row != null) rows.add(row)
    }
    stepC_writeCsv(channel, rows)
}
println "\nSTEP 3 DONE. Results are in: " + OUTPUT_FOLDER
// ============================================================================
//  STEP B  -  BACKGROUND SUBTRACTION, THRESHOLD AND MEASUREMENT OF ONE IMAGE
// ============================================================================
// Returns one CSV row (a list of values in the order of CSV_HEADER).
def stepB_analyzeImage(File channelFile, Map channel, String imageName) {
    ImagePlus image = IJ.openImage(channelFile.getAbsolutePath())
    if (image == null) {
        println "   ERROR: could not open " + channelFile
        return null
    }
    if (image.getBitDepth() != 8) {
        println "   ERROR: " + channelFile.getName() + " is not an 8-bit image - run the split step first. Skipped."
        return null
    }
    def baseName = imageName + channelSuffix(channel)
    def rangeUsed = readNote(image, "EightBitRange")

    // Load the QuPath annotation boundary saved by Step 2. This is separate
    // from your active lasso selection used for manual pixel subtraction.
    Roi region = null
    if (MEASURE_INSIDE_ANNOTATION_ONLY) {
        File roiFile = new File(channelFile.getParentFile(),
                stripTiffExtension(channelFile.getName()) + "_annotation.roi")
        if (roiFile.exists()) {
            region = new RoiDecoder(roiFile.getAbsolutePath()).getRoi()
        } else {
            println "   WARNING: no saved annotation ROI for " + channelFile.getName() +
                    "; measuring the full image."
        }
    }

    // ---- Set Scale: tell ImageJ how big a pixel is ----
    double pixelSize = PIXEL_SIZE_UM
    Calibration scale = image.getCalibration()
    scale.pixelWidth = pixelSize
    scale.pixelHeight = pixelSize
    scale.setUnit("um")

    // ---- Subtract Background (rolling ball / sliding paraboloid) ----
    ImageProcessor pixels = image.getProcessor()
    new BackgroundSubtracter().rollingBallBackground(
            pixels,
            (double) channel.rollingBallRadius,   // radius
            false,                                // false = subtract it (do not just show the background)
            (boolean) channel.lightBackground,    // "Light background" tick-box
            (boolean) channel.slidingParaboloid,  // "Sliding paraboloid" tick-box
            true,                                 // smooth first (ImageJ's default)
            true)                                 // correct the corners (ImageJ's default)
    image.setRoi(region)
    saveTiff(image, new File(new File(channelFolder(channel), BGSUB_SUBFOLDER), baseName + "_bgsub.tif"))

    // ---- Threshold: save a black/white picture of what counts as signal ----
    int lowest = channel.thresholdMin
    int highest = channel.thresholdMax
    int[] lookup = new int[256]                    // for each brightness 0-255: white or black?
    for (int v = 0; v < 256; v++) {
        lookup[v] = (v >= lowest && v <= highest) ? 255 : 0
    }
    ImageProcessor maskPixels = pixels.duplicate()
    maskPixels.resetRoi()
    maskPixels.applyTable(lookup)
    def maskImage = new ImagePlus(baseName + "_thresh", maskPixels)
    maskImage.setCalibration(scale)
    maskImage.setRoi(region)
    saveTiff(maskImage, new File(new File(channelFolder(channel), THRESH_SUBFOLDER), baseName + "_thresh.tif"))

    // ---- Measure ----
    // First the whole region (annotation minus artifacts, or the whole picture)...
    pixels.resetThreshold()
    if (region != null) pixels.setRoi(region) else pixels.resetRoi()
    ImageStatistics whole = ImageStatistics.getStatistics(
            pixels, Measurements.AREA | Measurements.MEAN | Measurements.MIN_MAX, scale)

    // ...then only the pixels inside the threshold ("Limit to threshold" in ImageJ).
    pixels.setThreshold((double) lowest, (double) highest, ImageProcessor.NO_LUT_UPDATE)
    ImageStatistics signal = ImageStatistics.getStatistics(
            pixels, Measurements.AREA | Measurements.MEAN | Measurements.LIMIT, scale)

    double signalMean  = signal.pixelCount > 0 ? signal.mean : 0     // avoid "NaN" when nothing is above threshold
    double signalArea  = signal.pixelCount > 0 ? signal.area : 0
    double percentArea = whole.pixelCount > 0 ? 100.0 * signal.pixelCount / whole.pixelCount : 0

    println "   analysed: " + baseName + String.format(Locale.US, "   (%%Area = %.3f)", percentArea)
    image.close()

    // The values must be in the same order as CSV_HEADER.
    return [
        imageName,
        channel.name,
        whole.area,
        signalArea,
        percentArea,
        signalMean,
        signalArea * signalMean,
        signal.pixelCount * signalMean,
        whole.mean,
        whole.min,
        whole.max,
        pixelSize,
        channel.rollingBallRadius,
        lowest,
        highest,
        rangeUsed
    ]
}


// ============================================================================
//  STEP C  -  WRITE / UPDATE THE CSV FILE OF ONE CHANNEL
// ============================================================================
// Rows of images that were analysed before are replaced by their new values;
// rows of other images already in the file are kept.
def stepC_writeCsv(Map channel, List newRows) {
    if (newRows.isEmpty()) return
    def csvFile = new File(channelFolder(channel), channel.name + "_Measurements.csv")
    def headerLine = CSV_HEADER.join(",")
    def newNames = newRows.collect { csvCell(it[0]) } as Set

    try {
        def keptLines = []
        if (csvFile.exists()) {
            def oldLines = csvFile.readLines("UTF-8").findAll { !it.trim().isEmpty() }
            if (!oldLines.isEmpty() && oldLines[0] == headerLine) {
                // Keep the old rows, except those of images we have just re-measured.
                keptLines = oldLines.drop(1).findAll { line -> !newNames.any { line.startsWith(it + ",") } }
            } else if (!oldLines.isEmpty()) {
                // The old file has different columns (made by another version): keep it as a backup.
                def backup = new File(channelFolder(channel), channel.name + "_Measurements_old_" + System.currentTimeMillis() + ".csv")
                csvFile.renameTo(backup)
                println "   NOTE: existing CSV had different columns; it was kept as " + backup.getName()
            }
        }
        def allLines = [headerLine] + keptLines + newRows.collect { row -> row.collect { csvCell(it) }.join(",") }
        csvFile.setText(allLines.join(System.lineSeparator()) + System.lineSeparator(), "UTF-8")
        println "   CSV written: " + csvFile + "   (" + (allLines.size() - 1) + " row(s) in total)"
    } catch (IOException problem) {
        println "   ERROR: could not write " + csvFile + " - is it open in Excel? Close it and run again. (" + problem.getMessage() + ")"
    }
}


// Stops the script early, with a readable message, if a setting is unusable.
def checkSettings() {
    def names = [] as Set
    for (channel in CHANNELS) {
        for (key in ["number", "name", "rollingBallRadius", "slidingParaboloid", "lightBackground", "thresholdMin", "thresholdMax"]) {
            if (channel[key] == null) {
                throw new IllegalArgumentException("SETTINGS problem: a channel block is missing '" + key + "'.")
            }
        }
        if (channel.thresholdMin < 0 || channel.thresholdMax > 255 || channel.thresholdMin > channel.thresholdMax) {
            throw new IllegalArgumentException("SETTINGS problem (" + channel.name + "): thresholds must satisfy 0 <= thresholdMin <= thresholdMax <= 255.")
        }
        if (channel.eightBitRange != null && !(channel.eightBitRange.size() == 2 && channel.eightBitRange[1] > channel.eightBitRange[0])) {
            throw new IllegalArgumentException("SETTINGS problem (" + channel.name + "): eightBitRange must be null or [low, high] with high bigger than low.")
        }
        if (!names.add(channel.name.toLowerCase())) {
            throw new IllegalArgumentException("SETTINGS problem: two channels are both called '" + channel.name + "'. Names must be different.")
        }
    }
    if (!(PIXEL_SIZE_UM > 0)) {
        throw new IllegalArgumentException("SETTINGS problem: PIXEL_SIZE_UM must be bigger than 0.")
    }
}

// The folder of one channel, e.g.  <OUTPUT_FOLDER>/GluN1
def channelFolder(Map channel) {
    return new File(OUTPUT_FOLDER, channel.name)
}

// The ending added to file names of one channel, e.g.  "_glun1"
def channelSuffix(Map channel) {
    return "_" + channel.name.toLowerCase()
}

// All .tif / .tiff files in a folder, in alphabetical order.
def listTiffs(File folder) {
    def files = folder.listFiles()
    if (files == null) return []
    return files.findAll { it.isFile() && (it.getName().toLowerCase().endsWith(".tif") || it.getName().toLowerCase().endsWith(".tiff")) }
                .sort { it.getName() }
}

// "picture.tif" -> "picture"
def stripTiffExtension(String name) {
    return name.replaceAll(/(?i)\.tiff?$/, "")
}

// Removes an ending from a text if it is there:  ("abc_glun1", "_glun1") -> "abc"
def stripEnding(String text, String ending) {
    return text.toLowerCase().endsWith(ending.toLowerCase()) ? text.substring(0, text.length() - ending.length()) : text
}

// Saves an image as TIFF (creating the folder if needed). Returns true if it worked.
def saveTiff(ImagePlus image, File file) {
    file.getParentFile().mkdirs()
    IJ.saveAsTiff(image, file.getAbsolutePath())
    if (!file.exists()) {
        println "   ERROR: could not save " + file
        return false
    }
    return true
}

// Reads one entry of the note that step A stored inside a channel TIFF,
// e.g. readNote(image, "EightBitRange") -> "312-3605".  "unknown" if it is missing.
def readNote(ImagePlus image, String key) {
    def note = image.getProperty("Info")
    if (note == null) return "unknown"
    for (line in note.toString().readLines()) {
        if (line.startsWith(key + "=")) return line.substring(key.length() + 1)
    }
    return "unknown"
}

// Formats one value for the CSV: numbers with up to 4 decimals, text in quotes if it contains a comma.
def csvCell(Object value) {
    if (value instanceof Integer || value instanceof Long) return value.toString()
    if (value instanceof Number) return String.format(Locale.US, "%.4f", value.doubleValue())
    def text = value.toString()
    return (text.contains(",") || text.contains('"')) ? '"' + text.replace('"', '""') + '"' : text
}


