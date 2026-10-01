package util

import org.opencv.core.{CvType, Mat}

import java.awt.image.{BufferedImage, DataBufferByte}

object Utils {

  implicit class agdBufferedImage(bi: BufferedImage) {

    private def to3ByteBGRType = {
      val convertedImage = new BufferedImage(bi.getWidth, bi.getHeight, BufferedImage.TYPE_3BYTE_BGR)
      val g = convertedImage.createGraphics()
      g.drawImage(bi, 0, 0, null)
      g.dispose()
      convertedImage
    }

    def toMat: Mat = {
      val bi2 = if (bi.getType != BufferedImage.TYPE_3BYTE_BGR) to3ByteBGRType else bi
      val data = bi2.getRaster.getDataBuffer.asInstanceOf[DataBufferByte].getData
      val mat = new Mat(bi2.getHeight, bi2.getWidth, CvType.CV_8UC3)
      mat.put(0, 0, data)
      mat
    }

    /** Independent copy of a region (getSubimage shares the raster with the source image). */
    def crop(x: Int, y: Int, width: Int, height: Int): BufferedImage = {
      val copy = new BufferedImage(width, height, BufferedImage.TYPE_3BYTE_BGR)
      val g = copy.createGraphics()
      g.drawImage(bi.getSubimage(x, y, width, height), 0, 0, null)
      g.dispose()
      copy
    }
  }

  /** Human readable duration, e.g. 3725 -> "1 hour 2 minutes 5 seconds". */
  def formatDuration(totalSeconds: Int): String = {
    def unit(value: Int, name: String) = if (value > 0) Some(s"$value $name${if (value > 1) "s" else ""}") else None

    Seq(
      unit(totalSeconds / 3600, "hour"),
      unit((totalSeconds % 3600) / 60, "minute"),
      unit(totalSeconds % 60, "second")
    ).flatten match {
      case Nil => "0 seconds"
      case parts => parts.mkString(" ")
    }
  }
}
