package com.github.dtaniwaki.akka_pusher

import org.specs2.mutable.Specification
import org.specs2.specification.process.RandomSequentialExecution

class UtilsSpec extends Specification
  with SpecHelper
  with RandomSequentialExecution
{
  "#byteArrayToString" should {
    "convert a byte array to a string" in {
      Utils.byteArrayToString(Array[Byte](10, 11, 12)) === "000000000000000000000000000a0b0c"
    }
  }
  "#md5" should {
    "generate hex digest md5" in {
      Utils.md5("foo") === "acbd18db4cc2f85cedef654fccc4a4d8"
    }
  }
  "#sha256" should {
    "generate hex digest sha256" in {
      Utils.sha256("secret", "foo") === "773ba44693c7553d6ee20f61ea5d2757a9a4f4a44d2841ae4e95b52e4cd62db4"
    }
    "generate hex digest sha256 of strings with 日本語" in {
      Utils.sha256("secret", "Digest me 日本語") === "b52446253d26c4bd19c1200e310ddc8ff3678f3422b2df6c47b153209cadec0b"
      // echo -n "Digest me 日本語" | openssl dgst -sha256 -hmac "secret"
    }
  }
}
