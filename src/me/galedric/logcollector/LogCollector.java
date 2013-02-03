package me.galedric.logcollector;

import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.net.Socket;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

import javax.swing.*;
import javax.swing.text.BadLocationException;

public final class LogCollector extends Thread {
	// Main configuration window
	public static void main(String[] args) {
		final JFrame f = new JFrame("LogCollector options");
		f.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
		Container p = f.getContentPane();
		
		// Username label and text field
		final JLabel     user_label = new JLabel("Uploader name: ", JLabel.TRAILING);
		final JTextField user_text  = new JTextField(10);
		
		user_label.setLabelFor(user_text);
		
		user_text.setText(System.getProperty("user.name"));
		user_text.setCaretPosition(user_text.getText().length());
		
		p.add(user_label);
		p.add(user_text);
		
		// Log path label and text field
		final JLabel     log_label = new JLabel("Log file: ", JLabel.TRAILING);
		final JTextField log_text  = new JTextField(20);
		
		log_label.setLabelFor(log_text);
		//log_text.setEnabled(false);
		
		p.add(log_label);
		p.add(log_text);
		
		// Brown and run buttons
		final JButton browse_btn = new JButton("Select log file");
		final JButton run_btn    = new JButton("Start collection");
		
		final JFileChooser fc = new JFileChooser("C:\\Program Files (x86)\\World of Warcraft\\Logs");
		fc.setDialogTitle("Select WoWCombatLog.txt");
		fc.setFileSelectionMode(JFileChooser.FILES_ONLY);
		fc.setFileFilter(new LogFilter());
		
		browse_btn.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent _) {
				if(fc.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
					log_text.setText(fc.getSelectedFile().getPath());
					//run_btn.setEnabled(true);
				}
			}
		});
		
		//run_btn.setEnabled(false);
		run_btn.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent e) {
				f.setVisible(false);
				f.dispose();
				
				LogCollector collector = new LogCollector(log_text.getText(), user_text.getText());
				collector.start();
			}
		});
		
		p.add(browse_btn);
		p.add(run_btn);
		
		// Build window layout
		p.setLayout(new SpringLayout());
		SpringUtilities.makeCompactGrid(p, 3, 2, 6, 6, 6, 6);
		
		// Display window
		f.pack();
		f.setResizable(false);
		f.setLocationRelativeTo(null); // center
		f.setVisible(true);
	}
	
	private File       log;     // The watched log file
	private String     user;    // Uploader's username
	private DateFormat df;      // Timestamp format for the log window
	private boolean    running; // Switch for the main watcher loop
	private Socket     sock;    // Socket to the Collector Server
	
	// A synchronized queue for the log window
	private ArrayBlockingQueue<String> logQueue;
	
	public LogCollector(String log, String user) {
		this.log  = new File(log);
		this.user = user;
		
		df = new SimpleDateFormat("[dd/MM HH:mm:ss] ");
		logQueue = new ArrayBlockingQueue<String>(100);

		running = true;
		SwingUtilities.invokeLater(new CollectorGUI());
	}
	
	// Write a line in the log window
	public void log(String str) {
		logQueue.add(df.format(new Date()) + str + "\n");
	}
	
	// Manage the handshake with the server and read the server messages
	public void handshake() throws Exception {
		log("Connecting to :8124...");
		sock = new Socket("", 8124);
		
		log("Connected! Sending username...");
		
		OutputStream os = sock.getOutputStream();
		os.write(user.getBytes("UTF-8"));
		os.flush();
		
		// Reader for the server messages
		final BufferedReader sock_reader = new BufferedReader(new InputStreamReader(sock.getInputStream(), "UTF-8"));

		// Read the handshake status
		String status = sock_reader.readLine();
		
		if(status != null && status.equals("OK")) {
			log("Ready to collect!\n");
			// Thread managing the incoming server messages
			new Thread() {
				public void run() {
					try {
						String msg;
						while((msg = sock_reader.readLine()) != null) {
							log("[S] " + msg);
						}
					} catch (Exception e) {}
					
					log("Error reading server msg. Collector stopped!");
					running = false;
				}
			}.start();
		} else {
			throw new Exception("Server refused connection: " + status);
		}
	}

	// Main collector function
	public void run() {
		if(log == null) {
			log("No log file selected.");
			return;
		}
		
		if(user.length() < 1) {
			log("Bad username.");
			return;
		}
		
		log("Log is " + log.getPath());
		log("Uploading as " + user + "\n");
		
		try {
			handshake();
			
			// Compressor
			Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION);
			
			// Output streams
			OutputStream         os  = sock.getOutputStream();
			DeflaterOutputStream dos = new DeflaterOutputStream(os, deflater, true);
			
			// Input streams
			FileInputStream   fis = new FileInputStream(log);
			InputStreamReader isr = new InputStreamReader(fis, "UTF-8");
			BufferedReader    br  = new BufferedReader(isr);
			
			// Skip old data
			br.skip(log.length());
			
			boolean first = true;
			int line_count = 0;
			
			log("Collection started...");
			
			// Read the log
			while(running) {
				String line = br.readLine();
				
				// Discard the first line (may be truncated)
				if(first) {
					first = false;
					continue;
				}
				
				if(line == null) {
					if(line_count > 0) {
						log("Collected " + line_count + " lines");
						line_count = 0;
						dos.flush();
					}
					Thread.sleep(10000);
				} else {
					dos.write(line.getBytes("UTF-8"));
					dos.write(new byte[]{'\r', '\n'});
					++line_count;
				}
			}
			
			// Should not be executed
			br.close();
			dos.close();
			sock.close();
		} catch (Exception e) {
			// Safety net for uncaught exceptions
			StringWriter sw = new StringWriter();
			PrintWriter  pw = new PrintWriter(sw);
			e.printStackTrace(pw);
			log("Uncaught Exception !\n" + sw.toString() + "\n" + e.getMessage());
		}
	}
	
	// The "log" window
	private class CollectorGUI implements Runnable {
		public void run() {
			final JFrame f = new JFrame("LogCollector");
			f.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
			
			final JTextArea ta = new JTextArea(15, 80);
			ta.setEditable(false);
			ta.setLineWrap(true);
			ta.setFont(new Font("Consolas", Font.PLAIN, 12));
			
			JScrollPane scroll = new JScrollPane(ta);
			scroll.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_NEVER);
			
			f.setLayout(new FlowLayout());
			f.add(scroll);
			
			f.pack();
			f.setResizable(false);
			f.setLocationRelativeTo(null);
			f.setVisible(true);
			
			// Thread reading the log queue and displaying messages
			new Thread() {
				public void run() {
					while(true) {
						try {
							String msg = logQueue.take();
							ta.append(msg);
							
							// Scroll to bottom
							ta.setCaretPosition(ta.getLineEndOffset(ta.getLineCount()-1));
						} catch (InterruptedException e) {
						} catch (BadLocationException e) {
						}
					}
				}
			}.start();
		}
	}
}
