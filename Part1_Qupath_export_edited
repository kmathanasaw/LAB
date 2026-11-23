/*
 * ============================================================================
 *  PART 1 of 2  -  EXPORT QUPATH ANNOTATIONS AS TIFF IMAGES
 *  (run this in QuPath)
 * ============================================================================
 *
 *  The whole workflow:
 *
 *    PART 1 (this file, in QuPath)   every annotation  ->  one multi-channel TIFF
 *    BY HAND (in ImageJ / Fiji)      open the TIFFs, mark any artifacts, save
 *    PART 2 (other file)             split channels, subtract background,
 *                                    threshold, measure, write one CSV per channel
 *
 *  ---------------------------------------------------------------------------
 *  HOW TO RUN PART 1
 *  ---------------------------------------------------------------------------
 *    1. Open your project in QuPath and draw + NAME your annotations as usual.
 *    2. In QuPath:  Automate  ->  Script editor  (older versions: "Show script editor").
 *    3. File -> Open...  and choose this file.
 *    4. Edit the SETTINGS section just below (mainly OUTPUT_FOLDER).
 *    5. Run it:
 *         - one slide  : open the slide, then  Run -> Run
 *         - all slides : Run -> Run for project   (pick the slides you want)
 *    6. Watch the log at the bottom of the script editor. It lists every
 *       exported image and finishes with "PART 1 DONE".
 *
 *  ---------------------------------------------------------------------------
 *  WHAT YOU GET
 *  ---------------------------------------------------------------------------
 *    <OUTPUT_FOLDER>/1_exported_tiffs/<slide name>_<annotation name>.tif
 *
 *    Each TIFF is the rectangle around one annotation, with all channels.
 *    The outline you drew in QuPath is stored in the image as a YELLOW OVERLAY
 *    LINE. Part 2 uses that yellow line to know where to measure - leave it in.
 *
 *  ---------------------------------------------------------------------------
 *  AFTER PART 1: REMOVING ARTIFACTS BY HAND IN IMAGEJ / FIJI
 *  ---------------------------------------------------------------------------
 *    For every exported TIFF that has an artifact (fold, bubble, dust, tear...):
 *      1. Open the TIFF in ImageJ / Fiji.
 *      2. Draw a selection around the artifact (freehand or polygon tool).
 *      3. Press  Ctrl+B  (Mac: Cmd+B)  =  Image > Overlay > Add Selection.
 *         The selection becomes a permanent line on the image.
 *      4. Repeat 2-3 for every artifact in that image.
 *      5. File > Save   (same name, same folder, keep it a TIFF).
 *
 *    Part 2 then leaves every marked artifact OUT of the measurements, and out
 *    of the measured area, in every channel. The pixels themselves are not
 *    changed, so you can always look at what was excluded and undo it.
 *
 *    Images without artifacts need nothing - leave them as they are.
 *
 *    DO NOT:  - delete the yellow outline (Image > Overlay > Remove Overlay)
 *             - rename or move the files, or save them as another type
 *             - change the image to 8-bit or split the channels (Part 2 does that)
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
// "import" lines tell the script which ready-made tools it is going to borrow
// from ImageJ (names starting with ij.) and from QuPath (starting with qupath.).
// Leave them alone.
// ----------------------------------------------------------------------------
import ij.IJ
import ij.ImagePlus
import ij.gui.Overlay
import java.awt.Color
import qupath.imagej.tools.IJTools
import qupath.lib.common.GeneralTools
import qupath.lib.regions.RegionRequest


// ############################################################################
// ##                                                                        ##
// ##   SETTINGS  -  this is the only part you normally need to change       ##
// ##                                                                        ##
// ############################################################################

// ---- Where should everything be saved? -------------------------------------
// Use forward slashes  /  even on Windows.  The folder is created if missing.
// Tip: use a different folder for each brain region / experiment.
// !!! Use exactly the same OUTPUT_FOLDER in Part 2.
OUTPUT_FOLDER = "E:/VA Chronic stress GluN1 + psd95 staining/PFC analysis"

// 1 = export at full resolution (normal).
// 2 = half resolution (4x smaller files), etc.  If you change this, the pixel
// size in Part 2 must be multiplied by the same number (the log tells you).
DOWNSAMPLE = 1


// Pixel calibration for exported images. 40x images = 0.137um
PIXEL_SIZE_UM = 0.137

// true = rename each annotation inside QuPath to "<slide name>_<annotation name>"
// (the old script did this).  The exported files get that name either way.
RENAME_ANNOTATIONS_IN_QUPATH = true

// Name of the sub-folder for the exported TIFFs (must be the same in Part 2).
EXPORT_SUBFOLDER = "Multichannel TIFFs"

// ############################################################################
// ##   END OF SETTINGS  -  you should not need to edit anything below       ##
// ############################################################################


// The label given to the yellow annotation outline inside each TIFF.
// Part 2 looks for exactly this label, so it must be the same in both files.
OUTLINE_NAME = "QuPath annotation"


// ============================================================================
//  MAIN PROGRAM
// ============================================================================

exportFolder = new File(OUTPUT_FOLDER, EXPORT_SUBFOLDER)
exportedCount = exportAnnotations()

println "\nPART 1 DONE.  " + exportedCount + " image(s) saved in: " + exportFolder
println "Next: open the TIFFs in ImageJ/Fiji and mark artifacts (see the top of this file), then run Part 2."


// ============================================================================
//  EXPORT EVERY ANNOTATION OF THE OPEN SLIDE AS A TIFF
// ============================================================================
// Returns how many TIFF files were written.
def exportAnnotations() {
    def written = 0

    def imageData = getCurrentImageData()           // the slide currently open in QuPath
    if (imageData == null) {
        println "Nothing exported: no image is open in QuPath."
        return written
    }
    def server = imageData.getServer()              // gives access to the pixels

    // Slide name without the file extension (e.g. "Rat12_PFC")
    def slideName = GeneralTools.getNameWithoutExtension(server.getMetadata().getName())

    // Only annotations that enclose an area can be exported (not points or lines).
    def annotations = getAnnotationObjects().findAll { it.getROI() != null && it.getROI().isArea() }
    println "\n===== exporting ${annotations.size()} annotation(s) from ${slideName} ====="
    if (annotations.isEmpty()) {
        println "   (no area annotations found on this slide - nothing to export)"
        return written
    }

    // Tell the user the pixel size, because Part 2 needs it.
    def slidePixelSize = server.getPixelCalibration().getAveragedPixelSizeMicrons()
    if (!Double.isNaN(slidePixelSize)) {
        println "Pixel size of exported images: " + PIXEL_SIZE_UM * DOWNSAMPLE + " um"
        println "Use this value for PIXEL_SIZE_UM in Part 2."
    }

    exportFolder.mkdirs()
    def usedNames = [] as Set                       // to avoid two files with the same name
    def counter = 0

    for (annotation in annotations) {
        counter++

        // ---- build the name "<slide>_<annotation>" ----
        def annotationName = annotation.getName()
        if (annotationName == null || annotationName.trim().isEmpty()) {
            annotationName = "Annotation" + counter              // annotation was never named
        }
        def fullName = annotationName
        if (!annotationName.startsWith(slideName + "_")) {       // do not add the slide name twice
            fullName = slideName + "_" + annotationName          //   when the script is re-run
        }
        if (RENAME_ANNOTATIONS_IN_QUPATH) {
            annotation.setName(fullName)
        }

        // ---- make it a legal, unique file name ----
        def fileName = fullName.replaceAll('[\\\\/:*?"<>|]', "_")   // characters Windows forbids
        def uniqueName = fileName
        def n = 2
        while (usedNames.contains(uniqueName.toLowerCase())) {     // two annotations share a name
            uniqueName = fileName + "_" + n
            n++
        }
        usedNames.add(uniqueName.toLowerCase())

        def outFile = new File(exportFolder, uniqueName + ".tif")
        if (outFile.exists()) {
            println "   NOTE: " + outFile.getName() + " already existed and is replaced (any artifact marks in it are lost)."
        }
        println "   exporting: " + uniqueName
        try {
            // Ask QuPath for the pixels inside the annotation's bounding rectangle...
            def roi = annotation.getROI()
            def request = RegionRequest.createInstance(server.getPath(), DOWNSAMPLE, roi)
            def pathImage = IJTools.convertToImagePlus(server, request)
            ImagePlus image = pathImage.getImage()                 // ...as an ImageJ image

            // Store the drawn outline in the image as a labelled yellow overlay line.
            // (An overlay stays with the image even while other selections are
            //  drawn on it in ImageJ, which is what makes the artifact step safe.)
            def outline = IJTools.convertToIJRoi(roi, pathImage)
            outline.setName(OUTLINE_NAME)
            outline.setStrokeColor(Color.YELLOW)
            def overlay = new Overlay()
            overlay.add(outline)
            image.setOverlay(overlay)
            image.setTitle(uniqueName)

            IJ.saveAsTiff(image, outFile.getAbsolutePath())
            if (outFile.exists()) {
                written++
            } else {
                println "   ERROR: could not save " + outFile
            }
            image.close()
        } catch (Throwable problem) {
            println "   ERROR: could not export " + uniqueName + " (" + problem + ")"
            println "          If the annotation is extremely large, try DOWNSAMPLE = 2 or a smaller annotation."
        }
    }
    return written
}
