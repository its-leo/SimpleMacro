package cv

import nu.pattern.OpenCV
import org.opencv.core._
import org.opencv.imgproc.Imgproc
import util.Utils.agdBufferedImage

import java.awt.image.BufferedImage

object Image {

  OpenCV.loadLocally()

  val DefaultThreshold = 0.85

  /**
   * Template sizes tried by a multi-scale search, ordered by likelihood. They cover common zoom and
   * display scaling changes (e.g. browser zoom 50 % - 200 %) between capturing and executing.
   */
  val MultiScaleFactors: Seq[Double] = Seq(1.0, 0.9, 1.1, 0.8, 1.25, 0.75, 1.5, 0.67, 1.75, 2.0, 0.5)

  /** Resized elements never match perfectly, so their threshold is lowered by this amount. */
  val ScaledThresholdTolerance = 0.1

  implicit class agdImageBuffer(image: BufferedImage) {

    /**
     * Searches the needle in this image. With several scales, the needle is resized by each factor and the
     * best match above the threshold wins; the search stops early once a near perfect match is found.
     */
    def findBestMatch(needle: BufferedImage, threshold: Double = DefaultThreshold, scales: Seq[Double] = Seq(1.0)): Option[Match] = {
      val sourceMat = image.toMat
      val graySourceMat = new Mat()
      val templateMat = needle.toMat
      val grayTemplateMat = new Mat()

      try {
        // Ensure both images are in the same color space (grayscale)
        Imgproc.cvtColor(sourceMat, graySourceMat, Imgproc.COLOR_BGR2GRAY)
        Imgproc.cvtColor(templateMat, grayTemplateMat, Imgproc.COLOR_BGR2GRAY)

        // Resized elements are rendered slightly differently (anti-aliasing, font hinting). Blurring both images
        // removes these pixel-level differences while keeping the shape of the element.
        val multiScale = scales.exists(_ != 1.0)
        if (multiScale) {
          Imgproc.GaussianBlur(graySourceMat, graySourceMat, new Size(5, 5), 0)
          Imgproc.GaussianBlur(grayTemplateMat, grayTemplateMat, new Size(5, 5), 0)
        }

        var best: Option[Match] = None
        val remaining = scales.iterator
        while (remaining.hasNext && !best.exists(_.confidence > 0.98)) {
          val scale = remaining.next()
          matchAtScale(graySourceMat, grayTemplateMat, scale)
            .filter(m => m.confidence > (if (scale == 1.0) threshold else threshold - ScaledThresholdTolerance))
            .filter(m => best.forall(_.confidence < m.confidence))
            .foreach(m => best = Some(m))
        }
        best
      } finally {
        // Mats hold native memory that is not tracked by the JVM garbage collector
        Seq(sourceMat, graySourceMat, templateMat, grayTemplateMat).foreach(_.release())
      }
    }
  }

  private def matchAtScale(source: Mat, template: Mat, scale: Double): Option[Match] = {
    val width = Math.round(template.cols() * scale).toInt
    val height = Math.round(template.rows() * scale).toInt
    // matchTemplate requires the template to fit inside the source image; tiny templates match everywhere
    if (width < 4 || height < 4 || width > source.cols() || height > source.rows()) return None

    val scaled = new Mat()
    val result = new Mat()
    try {
      if (scale == 1.0) template.copyTo(scaled)
      else Imgproc.resize(template, scaled, new Size(width, height), 0, 0, if (scale < 1) Imgproc.INTER_AREA else Imgproc.INTER_LINEAR)

      Imgproc.matchTemplate(source, scaled, result, Imgproc.TM_CCOEFF_NORMED)
      val mmr = Core.minMaxLoc(result)
      // A uniform template (e.g. a plain color) has no variance and yields NaN/Infinity
      if (mmr.maxVal.isNaN || mmr.maxVal.isInfinite) None
      else Some(Match(new Rect(mmr.maxLoc, new Size(width, height)), mmr.maxVal, scale))
    } finally {
      scaled.release()
      result.release()
    }
  }
}
