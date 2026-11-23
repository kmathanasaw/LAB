/*
 * ============================================================================
 *  PART 2 of 2  -  SPLIT CHANNELS, ANALYSE, AND WRITE THE CSV FILES
 *  (run this in Fiji, or in QuPath - see below)
 * ============================================================================
 *
 *  Run this AFTER Part 1 has exported the TIFFs, and after you have marked any
 *  artifacts by hand in ImageJ/Fiji (instructions are at the top of Part 1).
 *
 *  What it does with every TIFF in  <OUTPUT_FOLDER>/1_exported_tiffs :
 *
 *    STEP A  split it into its channels (8-bit), each channel in its own folder
 *    STEP B  for every channel image: subtract background, threshold, measure,
 *            using that channel's own settings
 *    STEP C  write the measurements to one CSV spreadsheet per channel
 *
 *  Marked artifacts are left out of the measurements in every channel.
 *
 *  ---------------------------------------------------------------------------
 *  HOW TO RUN PART 2
 *  ---------------------------------------------------------------------------
 *  This file is written in a language called Groovy. It needs no slide open -
 *  it simply works through the folder. Use whichever program you have:
 *
 *    IN FIJI  (the "batteries included" ImageJ - recommended):
 *      1. File > Open...  and choose this file (or drag it onto the Fiji bar).
 *         It opens in Fiji's script editor.
 *      2. Check that the Language menu of the editor says "Groovy".
 *      3. Edit the SETTINGS below, then press  Run.
 *
 *    IN QUPATH  (if you only have plain ImageJ, which cannot run Groovy):
 *      1. Automate > Script editor,  File > Open...  and choose this file.
 *      2. Edit the SETTINGS below, then  Run > Run .
 *         (NOT "Run for project" - once is enough, it does the whole folder.)
 *
 *  Either way, watch the log: it lists every image and ends with "PART 2 DONE".
 *
 *  ---------------------------------------------------------------------------
 *  WHAT YOU GET  (inside OUTPUT_FOLDER)
 *  ---------------------------------------------------------------------------
 *    GluN1/                            one folder per channel (named in CHANNELS)
 *        unprocessed/                  the single-channel 8-bit image
 *        bgsub/                        after background subtraction
 *        thresh/                       black/white picture: white = counted as signal
 *        GluN1_Measurements.csv        one row per image  (open with Excel)
 *    PSD95/
 *        ... same layout ...
 *
 *    When you open one of these images in ImageJ, the selection drawn on it is
 *    the exact region that was measured (annotation minus artifacts).
 *
 *  It is safe to run Part 2 again (for example after changing a threshold or
 *  marking another artifact). Images are overwritten and their rows in the CSV
 *  are REPLACED, not duplicated.
 *
 *  ---------------------------------------------------------------------------
 *  READING TIPS FOR NON-PROGRAMMERS
 *  ---------------------------------------------------------------------------
 *    - Anything after  //  on a line, or between  /*  and  *\/ , is a comment:
 *      a note for humans. The computer ignores it.
 *    - Text values need "quotes".  Numbers do not.
 *    - true / false are on/off switches (always lower-case, no quotes).
 *    - You should only ever need to edit the SETTINGS section.
 * ============================================================================
 */


// ----------------------------------------------------------------------------
// "import" lines tell the script which ready-made ImageJ tools it is going to
// borrow. Leave them alone.
// ----------------------------------------------------------------------------
import ij.IJ
import ij.ImagePlus
import ij.gui.Roi
import ij.gui.ShapeRoi
import ij.measure.Calibration
import ij.measure.Measurements
import ij.plugin.filter.BackgroundSubtracter
import ij.process.ByteProcessor
import ij.process.ColorProcessor
import ij.process.ImageProcessor
import ij.process.ImageStatistics


// ############################################################################
// ##                                                                        ##
// ##   SETTINGS  -  this is the only part you normally need to change       ##
// ##                                                                        ##
// ############################################################################

// ---- Where are the files? ----------------------------------------------------
// Exactly the same OUTPUT_FOLDER as in Part 1.  Use forward slashes  /  .
OUTPUT_FOLDER = "E:/VA Chronic stress GluN1 + psd95 staining/PFC analysis"


// ---- Which steps should run? -------------------------------------------------
// Normally leave both on.
//   * If you ONLY changed a threshold or a background radius, you can set
//     RUN_SPLIT = false to save time: the channel images made last time are
//     analysed again.
//   * If you marked new artifacts, or changed eightBitRange or
//     MEASURE_INSIDE_ANNOTATION_ONLY, RUN_SPLIT must be true.
RUN_SPLIT   = true     // exported TIFFs   ->  one 8-bit image per channel
RUN_ANALYZE = true     // channel images   ->  bgsub, threshold, measure, CSV


// ---- Pixel size --------------------------------------------------------------
// Size of ONE pixel of the exported TIFFs in micrometres. It depends on the
// objective used on the VS200 (e.g. 40x = 0.137 and 20x = 0.274). It is what turns pixel
// counts into um^2 in the CSV.  Part 1 prints the right number in its log.
PIXEL_SIZE_UM = 0.137


// ---- The channels ------------------------------------------------------------
// One block  [ ... ]  per channel you want to analyse.  Channels that are not
// listed (for example a DAPI channel 3) are simply ignored.
// To add a channel: copy a whole block including its [ ], paste it below the
// last one, and change the values.  Blocks are separated by commas.
//
//   number            which channel of the image (1 = first channel, 2 = second ...)
//   name              your name for it; used for the folder, file and CSV names
//   rollingBallRadius "Subtract Background" radius in pixels. Should be bigger
//                     than the largest object that is real signal.
//   slidingParaboloid true = the "Sliding paraboloid" tick-box in ImageJ
//   lightBackground   true = the "Light background" tick-box (false for fluorescence)
//   thresholdMin      pixels with brightness from thresholdMin ...
//   thresholdMax      ... to thresholdMax (0-255) are counted as signal
//   eightBitRange     how the original brightness is squeezed into 0-255:
//                       null        = automatic (same as the old scripts)
//                       [0, 4000]   = always map 0 -> 0 and 4000 -> 255
//                     See "IMPORTANT NOTE ABOUT 8-BIT CONVERSION" at the bottom.
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


// ---- Less commonly changed options -------------------------------------------

// true  = measure only inside the outline you drew in QuPath (the yellow line).
// false = measure the whole rectangular image that surrounds the outline.
// Marked artifacts are left out in both cases.
MEASURE_INSIDE_ANNOTATION_ONLY = true

// Names of the sub-folders.  EXPORT_SUBFOLDER must be the same as in Part 1.
EXPORT_SUBFOLDER      = "Multichannel Tiffs"
SPLIT_ORIGINAL_SUBFOLDER = "split_original"
UNPROCESSED_SUBFOLDER = "unprocessed"
BGSUB_SUBFOLDER       = "bgsub"
THRESH_SUBFOLDER      = "thresh"

// ############################################################################
// ##   END OF SETTINGS  -  you should not need to edit anything below       ##
// ############################################################################


// The label Part 1 gave to the yellow annotation outline. Must match Part 1.
OUTLINE_NAME = "QuPath annotation"

// The column titles of the CSV files, in order.
CSV_HEADER = [
    "Image",                      // slide name + annotation name
    "Channel",                    // channel name from CHANNELS
    "Region_Area_um2",            // area that was measured (artifacts already taken out)
    "Signal_Area_um2",            // area of the pixels inside the threshold
    "Percent_Area",               // Signal_Area / Region_Area x 100   ("%Area" in ImageJ)
    "Mean_of_Signal",             // average brightness (0-255) of the thresholded pixels
    "IntDen_of_Signal",           // Signal_Area_um2 x Mean_of_Signal  ("IntDen" in ImageJ)
    "RawIntDen_of_Signal",        // sum of the brightness of all thresholded pixels
    "Mean_of_Whole_Region",       // average brightness of the whole region (after bgsub)
    "Min_of_Whole_Region",
    "Max_of_Whole_Region",
    "Artifacts_Excluded",         // how many marked artifacts were left out
    "Pixel_Size_um",              // the settings that were used, so that every
    "Rolling_Ball_Radius",        // row records how it was produced
    "Threshold_Min",
    "Threshold_Max",
    "EightBit_Range_Used"
]


// ============================================================================
//  MAIN PROGRAM  -  runs the steps in order.  The detailed work of each step
//  is written further down in its own clearly labelled block.
// ============================================================================

checkSettings()

exportFolder = new File(OUTPUT_FOLDER, EXPORT_SUBFOLDER)

// ---------- STEP A : split channels -------------------------------------------
// "channelImages" becomes a list of [file, channel settings, image name] entries.
channelImages = []
if (RUN_SPLIT) {
    mergedTiffs = listTiffs(exportFolder)
    println "\n===== STEP A: splitting ${mergedTiffs.size()} image(s) into channels ====="
    if (mergedTiffs.isEmpty()) {
        println "   No TIFF files found in " + exportFolder + " - is OUTPUT_FOLDER right, and has Part 1 been run?"
    }
    for (tiff in mergedTiffs) {
        channelImages.addAll(stepA_splitChannels(tiff))
    }
} else {
    // Splitting switched off: use every image already in the "unprocessed" folders.
    for (channel in CHANNELS) {
        for (file in listTiffs(new File(channelFolder(channel), UNPROCESSED_SUBFOLDER))) {
            imageName = stripEnding(stripTiffExtension(file.getName()), channelSuffix(channel))
            channelImages.add([file: file, channel: channel, imageName: imageName])
        }
    }
}

// ---------- STEP B + C : analyse every channel image and write the CSVs --------
if (RUN_ANALYZE) {
    println "\n===== STEP B: analysing ${channelImages.size()} channel image(s) ====="
    for (channel in CHANNELS) {
        rows = []                                   // the new CSV rows of this channel
        for (item in channelImages) {
            if (item.channel.is(channel)) {         // only the images of this channel
                row = stepB_analyzeImage(item.file, channel, item.imageName)
                if (row != null) rows.add(row)
            }
        }
        stepC_writeCsv(channel, rows)
    }
}

println "\nPART 2 DONE.  Results are in: " + OUTPUT_FOLDER


// ============================================================================
//  STEP A  -  SPLIT ONE MULTI-CHANNEL TIFF INTO SINGLE-CHANNEL 8-BIT IMAGES
// ============================================================================
// Returns a list with one entry per channel image that was written.
def stepA_splitChannels(File tiffFile) {
    def results = []
    def imageName = stripTiffExtension(tiffFile.getName())

    ImagePlus image = IJ.openImage(tiffFile.getAbsolutePath())
    if (image == null) {
        println "   ERROR: could not open " + tiffFile
        return results
    }

    // Work out where to measure: the annotation outline minus the marked artifacts.
    def found = findMeasuredRegion(image, imageName)
    if (found.empty) {
        println "   WARNING: " + imageName + " - nothing is left to measure after removing the artifacts. Skipped."
        image.close()
        return results
    }
    Roi region = found.region                       // null means "the whole picture"

    def isColourPhoto = (image.getBitDepth() == 24) // RGB image (brightfield) instead of fluorescence
    def channelCount = isColourPhoto ? 3 : image.getNChannels()

    for (channel in CHANNELS) {
        if (channel.number > channelCount) {
            println "   WARNING: " + imageName + " has only " + channelCount + " channel(s); channel " +
                    channel.number + " (" + channel.name + ") skipped."
            continue
        }

        ImageProcessor pixels8bit
        ImageProcessor originalPixels
        def rangeText

        if (isColourPhoto) {
            // RGB image: channel 1 = red, 2 = green, 3 = blue. Already 0-255.
            def total = image.getWidth() * image.getHeight()
            byte[] r = new byte[total]; byte[] g = new byte[total]; byte[] b = new byte[total]
            ((ColorProcessor) image.getProcessor()).getRGB(r, g, b)
            pixels8bit = new ByteProcessor(image.getWidth(), image.getHeight(), [r, g, b][channel.number - 1])
            // Save a copy of the original RGB channel.
            originalPixels = pixels8bit.duplicate()
            rangeText = "0-255"
        } else {
            // Take a copy of just this channel's pixels.
            image.setPosition(channel.number, 1, 1)
            ImageProcessor pixels = image.getStack().getProcessor(image.getStackIndex(channel.number, 1, 1)).duplicate()

            // Keep a separate copy at the original bit depth.
            originalPixels = pixels.duplicate()
            
            if (pixels.getBitDepth() == 8) {
                // Already 8-bit: nothing to convert.
                pixels8bit = pixels
                rangeText = "0-255"
            } else {
                // Decide which original brightness becomes 0 and which becomes 255.
                double low, high
                if (channel.eightBitRange != null) {
                    low  = channel.eightBitRange[0]                 // fixed range from the settings
                    high = channel.eightBitRange[1]
                } else {
                    low  = image.getDisplayRangeMin()               // automatic: the range stored
                    high = image.getDisplayRangeMax()               //   with the image
                    if (!(high > low)) {                            // nothing stored: darkest and
                        pixels.resetMinAndMax()                     //   brightest pixel of this image
                        low = pixels.getMin(); high = pixels.getMax()
                    }
                }
                pixels.setMinAndMax(low, high)
                pixels8bit = pixels.convertToByte(true)             // true = rescale using low/high
                rangeText = Math.round(low) + "-" + Math.round(high)
            }
        }

        // Wrap the pixels up as a new image, attach the region to measure, and save it.
        // Save the original split channel before 8-bit conversion.
        def originalImage = new ImagePlus(
            imageName + channelSuffix(channel) + "_original",
            originalPixels
        )
        originalImage.setRoi(region)
        
        def originalFile = new File(
            new File(channelFolder(channel), SPLIT_ORIGINAL_SUBFOLDER),
            imageName + channelSuffix(channel) + "_original.tif"
        )
        
        saveTiff(originalImage, originalFile)
        originalImage.close()
        
        def channelImage = new ImagePlus(imageName + channelSuffix(channel), pixels8bit)
        channelImage.setRoi(region)
        // A short note stored inside the TIFF; step B reads it back for the CSV.
        channelImage.setProperty("Info", "EightBitRange=" + rangeText + "\nArtifactsExcluded=" + found.artifactCount)
        def outFile = new File(new File(channelFolder(channel), UNPROCESSED_SUBFOLDER),
                               imageName + channelSuffix(channel) + ".tif")
        if (saveTiff(channelImage, outFile)) {
            results.add([file: outFile, channel: channel, imageName: imageName])
            println "   split: " + outFile.getName() + "   (brightness " + rangeText + " -> 0-255, " +
                    found.artifactCount + " artifact(s) excluded)"
        }
    }
    image.close()
    return results
}


// ----------------------------------------------------------------------------
//  Finds the region to measure in one exported TIFF.
//    - The yellow line labelled "QuPath annotation" (made by Part 1) is the outline.
//    - Every OTHER shape in the overlay is an artifact that was marked by hand
//      (Ctrl+B in ImageJ) and is cut out of the region.
//  Returns:  region (null = whole picture), artifactCount, empty (true = nothing left)
// ----------------------------------------------------------------------------
def findMeasuredRegion(ImagePlus image, String imageName) {
    Roi outline = null
    def artifacts = []

    def overlay = image.getOverlay()
    if (overlay != null) {
        for (int i = 0; i < overlay.size(); i++) {
            Roi shape = overlay.get(i)
            if (OUTLINE_NAME.equals(shape.getName()) && outline == null) {
                outline = shape                                     // the QuPath annotation
            } else if (shape.isArea()) {
                artifacts.add(shape)                                // a hand-marked artifact
            }
        }
    }
    if (outline == null) {
        println "   WARNING: " + imageName + " has no QuPath annotation outline (was the overlay removed?). The whole picture is measured."
    }
    if (image.getRoi() != null) {
        // A selection was still active when the file was saved, but it was never added to the overlay.
        println "   WARNING: " + imageName + " was saved with a selection that is NOT in the overlay; it is ignored. " +
                "If it marks an artifact, open the image, press Ctrl+B, and save again."
    }

    boolean useOutline = MEASURE_INSIDE_ANNOTATION_ONLY && outline != null
    if (artifacts.isEmpty()) {
        return [region: useOutline ? outline : null, artifactCount: 0, empty: false]
    }

    // Start from the outline (or the whole picture) and cut every artifact out of it.
    Roi start = useOutline ? outline : new Roi(0, 0, image.getWidth(), image.getHeight())
    ShapeRoi region = new ShapeRoi(start)
    for (artifact in artifacts) {
        region = region.not(new ShapeRoi(artifact))
    }
    def box = region.getBounds()
    return [region: region, artifactCount: artifacts.size(), empty: (box.width <= 0 || box.height <= 0)]
}


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
    def artifactCount = readNote(image, "ArtifactsExcluded")

    // Remember the region to measure (stored by step A), then remove it for now
    // so that the background subtraction is applied to the whole picture.
    Roi region = image.getRoi()
    image.deleteRoi()

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
        artifactCount,
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


// ============================================================================
//  SMALL HELPERS  -  little tools used by the steps above
// ============================================================================

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


/*
 * ============================================================================
 *  IMPORTANT NOTE ABOUT 8-BIT CONVERSION  (eightBitRange)
 * ============================================================================
 *  The VS200 usually records fluorescence with thousands of brightness levels
 *  (16-bit). The old macros began with  run("8-bit") , which squeezes those into
 *  256 levels (0-255) - and the thresholds (e.g. 24) are on that 0-255 scale.
 *
 *  HOW the squeezing is done matters:
 *    - eightBitRange : null  (automatic) uses the brightness range stored with
 *      each exported image, which is normally its own darkest and brightest
 *      pixel. That means every image is stretched differently, so "24" does not
 *      correspond to the same true brightness in every image. A very bright
 *      artifact also changes that stretch, even when it is marked and excluded.
 *      (Changing Brightness & Contrast in ImageJ and then saving changes the
 *      stored range too.)
 *    - eightBitRange : [low, high]  uses the SAME mapping for every image, which
 *      makes thresholds comparable between images and animals, and is not
 *      affected by artifacts. Pick low/high once (e.g. from the Brightness &
 *      Contrast dialog on a few typical images) and keep them fixed for the
 *      whole study.
 *
 *  The range that was actually used is printed in the log and stored in the
 *  "EightBit_Range_Used" column of every CSV row.
 *  If your slides are already 8-bit, none of this applies (range is 0-255).
 *
 * ============================================================================
 *  IF YOU PREFER TO ERASE ARTIFACTS INSTEAD OF MARKING THEM
 * ============================================================================
 *  You can also paint over / clear an artifact in ImageJ (Edit > Clear) and
 *  save. Part 2 will work, but the erased pixels are then measured as black
 *  tissue: they still count in Region_Area_um2, so Percent_Area comes out too
 *  low. Marking with Ctrl+B (see Part 1) avoids that and is the recommended way.
 *
 * ============================================================================
 *  DIFFERENCES FROM THE OLD SCRIPTS  (worth knowing when comparing numbers)
 * ============================================================================
 *   - The "thresh" image is now a true black/white mask of the counted pixels
 *     (the old one was just another copy of the background-subtracted image).
 *   - The CSV columns are fixed by this script instead of depending on whatever
 *     was ticked in ImageJ's "Analyze > Set Measurements" on that computer.
 *   - Measurements are taken inside the drawn annotation outline
 *     (MEASURE_INSIDE_ANNOTATION_ONLY) and limited to the threshold.
 *   - File names no longer contain ".tif" in the middle
 *     (old: "name.tif_glun1.tif", new: "name_glun1.tif").
 */
