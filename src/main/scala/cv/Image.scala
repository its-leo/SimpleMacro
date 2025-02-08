package cv

import nu.pattern.OpenCV
import org.opencv.core._
import org.opencv.imgproc.Imgproc
import util.Utils.{agdBufferedImage, agdMat}

import java.awt.image.BufferedImage

object Image {

  OpenCV.loadLocally()

  implicit class agdImageBuffer(image: BufferedImage) {

    private def matchTemplate(needle: Mat) = {
      val mat: Mat = image.toMat
      val result = new Mat(mat.rows - needle.rows + 1, mat.cols - needle.cols + 1, CvType.CV_32FC1)
      Imgproc.matchTemplate(mat, needle, result, Imgproc.TM_CCOEFF_NORMED)
      result
    }


    def findBestMatch(needle: BufferedImage): Match = {
      val mat = needle.toMat
      val mmr = Core.minMaxLoc(matchTemplate(mat))
      Match(mat.getRect(mmr.maxLoc), mmr.maxVal)
    }

  }
}