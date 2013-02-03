package me.galedric.logcollector;

import java.io.File;
import javax.swing.filechooser.FileFilter;

public class LogFilter extends FileFilter {
	public boolean accept(File file) {
		return file.isDirectory() || file.getName().endsWith(".txt");
	}

	public String getDescription() {
		return "Log files (.txt)";
	}
}
