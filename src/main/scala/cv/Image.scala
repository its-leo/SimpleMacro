package cv

import nu.pattern.OpenCV
import org.opencv.core._
import org.opencv.imgproc.Imgproc
import util.Utils.agdBufferedImage

import java.awt.image.BufferedImage

object Image {

  OpenCV.loadLocally()

  val DefaultThreshold = 0.85

  implicit class agdImageBuffer(image: BufferedImage) {

    def findBestMatch(needle: BufferedImage, threshold: Double = DefaultThreshold): Option[Match] = {
      // matchTemplate requires the template to fit inside the source image
      if (needle.getWidth > image.getWidth || needle.getHeight > image.getHeight) return None

      val sourceMat = image.toMat
      val templateMat = needle.toMat
      val graySourceMat = new Mat()
      val grayTemplateMat = new Mat()
      val result = new Mat()

      try {
        // Ensure both images are in the same color space (grayscale)
        Imgproc.cvtColor(sourceMat, graySourceMat, Imgproc.COLOR_BGR2GRAY)
        Imgproc.cvtColor(templateMat, grayTemplateMat, Imgproc.COLOR_BGR2GRAY)

        Imgproc.matchTemplate(graySourceMat, grayTemplateMat, result, Imgproc.TM_CCOEFF_NORMED)

        val mmr = Core.minMaxLoc(result)
        val confidence = mmr.maxVal

        if (confidence > threshold) {
          Some(Match(new Rect(mmr.maxLoc, new Size(needle.getWidth, needle.getHeight)), confidence))
        } else None
      } finally {
        // Mats hold native memory that is not tracked by the JVM garbage collector
        Seq(sourceMat, templateMat, graySourceMat, grayTemplateMat, result).foreach(_.release())
      }
    }
  }
}
