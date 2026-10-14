import java.awt.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import javax.swing.*;

// Simple Huffman compressor for text files, with a small Swing window.
// Compile: javac HuffmanCompressor.java
// Run:     java HuffmanCompressor

public class huffman extends JFrame {
private File askWhereToSave(String suggestedName) {
    JFileChooser chooser = new JFileChooser();
    if (chosenFile != null) {
        chooser.setCurrentDirectory(chosenFile.getParentFile());
    }
    chooser.setSelectedFile(new File(suggestedName));
    chooser.setDialogTitle("Save file as");

    if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
        return null;
    }

    File file = chooser.getSelectedFile();

    if (file.exists()) {
        int answer = JOptionPane.showConfirmDialog(this,
                file.getName() + " already exists. Replace it?",
                "Confirm", JOptionPane.YES_NO_OPTION);
        if (answer != JOptionPane.YES_OPTION) {
            return null;
        }
    }
    return file;
}

    // ---------- Huffman tree node ----------
    static class Node {
        char ch;
        int freq;
        Node left, right;

        Node(char ch, int freq) {
            this.ch = ch;
            this.freq = freq;
        }

        Node(int freq, Node left, Node right) {
            this.freq = freq;
            this.left = left;
            this.right = right;
        }

        boolean isLeaf() {
            return left == null && right == null;
        }
    }

    // ---------- Huffman logic ----------

    // count how many times each character appears
    // (TreeMap keeps the characters sorted, so compress and decompress build the same tree)
    static TreeMap<Character, Integer> countChars(String text) {
        TreeMap<Character, Integer> freq = new TreeMap<>();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (freq.containsKey(c)) {
                freq.put(c, freq.get(c) + 1);
            } else {
                freq.put(c, 1);
            }
        }
        return freq;
    }

    // build the tree: keep joining the two smallest nodes
    static Node buildTree(TreeMap<Character, Integer> freq) {
        PriorityQueue<Node> queue = new PriorityQueue<>((a, b) -> a.freq - b.freq);

        for (char c : freq.keySet()) {
            queue.add(new Node(c, freq.get(c)));
        }

        while (queue.size() > 1) {
            Node first = queue.poll();
            Node second = queue.poll();
            queue.add(new Node(first.freq + second.freq, first, second));
        }
        return queue.poll();
    }

    // walk the tree: going left adds a 0, going right adds a 1
    static void makeCodes(Node node, String code, HashMap<Character, String> codes) {
        if (node == null) {
            return;
        }
        if (node.isLeaf()) {
            if (code.equals("")) {
                code = "0";   // file has only one kind of character
            }
            codes.put(node.ch, code);
            return;
        }
        makeCodes(node.left, code + "0", codes);
        makeCodes(node.right, code + "1", codes);
    }

    // ---------- Compress ----------
    static String compress(File input, File output) throws IOException {
        String text = new String(Files.readAllBytes(input.toPath()), StandardCharsets.UTF_8);

        TreeMap<Character, Integer> freq = countChars(text);
        Node root = buildTree(freq);

        HashMap<Character, String> codes = new HashMap<>();
        makeCodes(root, "", codes);

        // turn the whole text into a long string of 0s and 1s
        StringBuilder bits = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            bits.append(codes.get(text.charAt(i)));
        }

        DataOutputStream out = new DataOutputStream(new FileOutputStream(output));

        // header: how many characters in total, then every character with its count
        out.writeInt(text.length());
        out.writeInt(freq.size());
        for (char c : freq.keySet()) {
            out.writeChar(c);
            out.writeInt(freq.get(c));
        }

        // body: pack the bits, 8 at a time, into bytes
        for (int i = 0; i < bits.length(); i += 8) {
            int end = Math.min(i + 8, bits.length());
            String chunk = bits.substring(i, end);
            while (chunk.length() < 8) {
                chunk = chunk + "0";   // pad the last byte with zeros
            }
            out.writeByte(Integer.parseInt(chunk, 2));
        }
        out.close();

        // report for the text box
        long originalSize = input.length();
        long newSize = output.length();
        String report = "Compressed successfully!\n\n";
        report += "Original size   : " + originalSize + " bytes\n";
        report += "Compressed size : " + newSize + " bytes\n";
        if (originalSize > 0) {
            double saved = 100.0 - (newSize * 100.0 / originalSize);
            report += "Space saved     : " + String.format("%.1f", saved) + " %\n";
        }
        report += "\nCharacter codes:\n";
        for (char c : freq.keySet()) {
            report += show(c) + "  (used " + freq.get(c) + " times)  ->  " + codes.get(c) + "\n";
        }
        return report;
    }

    // ---------- Decompress ----------
    static String decompress(File input, File output) throws IOException {
        DataInputStream in = new DataInputStream(new FileInputStream(input));

        // read the header
        int totalChars = in.readInt();
        int uniqueChars = in.readInt();
        TreeMap<Character, Integer> freq = new TreeMap<>();
        for (int i = 0; i < uniqueChars; i++) {
            char c = in.readChar();
            int count = in.readInt();
            freq.put(c, count);
        }

        // rebuild the same tree
        Node root = buildTree(freq);

        // read the rest of the file and turn it back into 0s and 1s
        StringBuilder bits = new StringBuilder();
        int b;
        while ((b = in.read()) != -1) {
            String binary = Integer.toBinaryString(b);
            while (binary.length() < 8) {
                binary = "0" + binary;
            }
            bits.append(binary);
        }
        in.close();

        // follow the bits down the tree to get the characters back
        StringBuilder text = new StringBuilder();
        if (root != null && root.isLeaf()) {
            // only one kind of character in the file
            for (int i = 0; i < totalChars; i++) {
                text.append(root.ch);
            }
        } else if (root != null) {
            Node current = root;
            for (int i = 0; i < bits.length() && text.length() < totalChars; i++) {
                if (bits.charAt(i) == '0') {
                    current = current.left;
                } else {
                    current = current.right;
                }
                if (current.isLeaf()) {
                    text.append(current.ch);
                    current = root;
                }
            }
        }

        Files.write(output.toPath(), text.toString().getBytes(StandardCharsets.UTF_8));

        return "Decompressed successfully!\n\nSaved as: " + output.getName()
                + "\nCharacters restored: " + text.length() + "\n";
    }

    // make special characters readable in the text box
    static String show(char c) {
        if (c == ' ') return "[space]";
        if (c == '\n') return "[newline]";
        if (c == '\t') return "[tab]";
        if (c == '\r') return "[return]";
        return "'" + c + "'";
    }

    // ---------- Window (GUI) ----------
    JTextField fileBox = new JTextField();
    JTextArea outputBox = new JTextArea();
    File chosenFile = null;

    public huffman() {
        setTitle("Huffman Compressor");
        setSize(600, 450);
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setLocationRelativeTo(null);

        // top row: file name + browse button
        fileBox.setEditable(false);
        JButton browseButton = new JButton("Browse");
        JPanel topPanel = new JPanel(new BorderLayout(5, 5));
        topPanel.add(new JLabel(" File: "), BorderLayout.WEST);
        topPanel.add(fileBox, BorderLayout.CENTER);
        topPanel.add(browseButton, BorderLayout.EAST);

        // middle: where the results are shown
        outputBox.setEditable(false);
        outputBox.setFont(new Font("Monospaced", Font.PLAIN, 13));
        outputBox.setText("1. Click Browse and pick a text file\n2. Click Compress or Decompress\n");

        // bottom row: buttons
        JButton compressButton = new JButton("Compress");
        JButton decompressButton = new JButton("Decompress");
        JPanel bottomPanel = new JPanel();
        bottomPanel.add(compressButton);
        bottomPanel.add(decompressButton);

        add(topPanel, BorderLayout.NORTH);
        add(new JScrollPane(outputBox), BorderLayout.CENTER);
        add(bottomPanel, BorderLayout.SOUTH);

        // what each button does
        browseButton.addActionListener(e -> {
            JFileChooser chooser = new JFileChooser();
            if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
                chosenFile = chooser.getSelectedFile();
                fileBox.setText(chosenFile.getAbsolutePath());
            }
        });

        compressButton.addActionListener(e -> {
            if (chosenFile == null) {
                JOptionPane.showMessageDialog(this, "Please choose a file first.");
                return;
            }
            try {
                // ask the user where to save (download) the compressed file
                File output = askWhereToSave(chosenFile.getName() + ".huff");
                if (output == null) {
                    return;   // user pressed Cancel
                }
                outputBox.setText(compress(chosenFile, output));
                outputBox.setCaretPosition(0);
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(this, "Error: " + ex.getMessage());
            }
        });

        decompressButton.addActionListener(e -> {
            if (chosenFile == null) {
                JOptionPane.showMessageDialog(this, "Please choose a file first.");
                return;
            }
            try {
                String name = chosenFile.getName();
                if (name.endsWith(".huff")) {
                    name = name.substring(0, name.length() - 5);
                }
                File output = askWhereToSave("decoded_" + name);
                if (output == null) {
                    return;   // user pressed Cancel
                }
                outputBox.setText(decompress(chosenFile, output));
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(this, "Error: " + ex.getMessage()
                        + "\nMake sure you picked a .huff file.");
            }
        });
    }

    public static void main(String[] args) {
        huffman window = new huffman();
        window.setVisible(true);
    }
}