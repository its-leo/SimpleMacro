package util

import org.opencv.core.{CvType, Mat, Point, Rect}

import java.awt.image.{BufferedImage, DataBufferByte}

object Utils {

  implicit class agdBufferedImage(bi: BufferedImage) {

    private def to3ByteBGRType = {
      val convertedImage = new BufferedImage(bi.getWidth, bi.getHeight, BufferedImage.TYPE_3BYTE_BGR)
      convertedImage.getGraphics.drawImage(bi, 0, 0, null)
      convertedImage
    }

    def toMat: Mat = {
      val bi2 = if (bi.getType != BufferedImage.TYPE_3BYTE_BGR) to3ByteBGRType else bi
      val data = bi2.getRaster.getDataBuffer.asInstanceOf[DataBufferByte].getData
      val mat = new Mat(bi2.getHeight, bi2.getWidth, CvType.CV_8UC3)
      mat.put(0, 0, data)
      mat
    }
  }

  implicit class agdMat(m: Mat) {

    def getRect(point: Point): Rect = new Rect(point, new Point(point.x + m.cols, point.y + m.rows))
  }

}