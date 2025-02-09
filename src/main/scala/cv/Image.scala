package cv

import nu.pattern.OpenCV
import org.opencv.core._
import org.opencv.imgproc.Imgproc
import util.Utils.agdBufferedImage

import java.awt.image.BufferedImage

object Image {

  OpenCV.loadLocally()

  implicit class agdImageBuffer(image: BufferedImage) {

    def findBestMatch(needle: BufferedImage): Option[Match] = {
      val sourceMat: Mat = image.toMat
      val templateMat: Mat = needle.toMat

      // Ensure both images are in the same color space (e.g., grayscale)
      val graySourceMat = new Mat()
      val grayTemplateMat = new Mat()
      Imgproc.cvtColor(sourceMat, graySourceMat, Imgproc.COLOR_BGR2GRAY)
      Imgproc.cvtColor(templateMat, grayTemplateMat, Imgproc.COLOR_BGR2GRAY)

      val result = new Mat()
      Imgproc.matchTemplate(graySourceMat, grayTemplateMat, result, Imgproc.TM_CCOEFF_NORMED)

      val mmr = Core.minMaxLoc(result)
      val confidence = mmr.maxVal
      println("confidence: " + confidence)

      if(confidence > 0.85) {
        Some(Match(new Rect(mmr.maxLoc, new Size(needle.getWidth, needle.getHeight)), confidence))
      } else None
    }
  }
}