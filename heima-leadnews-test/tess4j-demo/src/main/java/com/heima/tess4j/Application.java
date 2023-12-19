package com.heima.tess4j;

import net.sourceforge.tess4j.ITesseract;
import net.sourceforge.tess4j.Tesseract;
import net.sourceforge.tess4j.TesseractException;

import java.io.File;

public class Application {
    public static void main(String[] args) throws TesseractException {
        //1 create testdata instance
        ITesseract tesseract = new Tesseract();

        //2 set path of the zitiku
        tesseract.setDatapath("F:\\java_project\\leadnews\\materials");

        //3 set language
        tesseract.setLanguage("chi_sim");

        //4 recognize picture
        File file = new File("E:\\test_materials\\tesstest.png");
        String result = tesseract.doOCR(file);
        System.out.println("the recognition result is" + result.replaceAll("\\r|\\n","-"));


    }
}
