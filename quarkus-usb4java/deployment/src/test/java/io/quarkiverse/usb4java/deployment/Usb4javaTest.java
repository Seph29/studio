package io.quarkiverse.usb4java.deployment;

import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.usb4java.Loader;

import io.quarkus.test.QuarkusUnitTest;

class Usb4javaTest {

    @RegisterExtension
    static final QuarkusUnitTest unitTest = new QuarkusUnitTest()
            .setArchiveProducer(() -> ShrinkWrap.create(JavaArchive.class));

    @Test
    void writeYourOwnUnitTest() {
        Loader.load();
        // assertEquals(2, Mat.zeros(10, 10, CvType.CV_8UC1).dims());
    }
}