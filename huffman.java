import javax.swing.*;
import java.awt.*;
import java.io.*;
import java.nio.file.Files;
import java.util.PriorityQueue;

/**
 * Huffman File Compressor with a Swing GUI.
 * Works on any file (text or binary) because it compresses raw bytes.
 *
 * Compile: javac HuffmanCompressor.java
 * Run:     java HuffmanCompressor
 */
public class HuffmanCompressor extends JFrame {

    // ---------------------------------------------------------------
    //  Huffman core
    // ---------------------------------------------------------------
    static class Node implements Comparable<Node> {
        final int symbol;      // 0-255 for leaves, -1 for internal nodes
        final long freq;
        final int order;       // tie-breaker so the tree is identical every time
        Node left, right;

        Node(int symbol, long freq, int order, Node left, Node right) {
            this.symbol = symbol;
            this.freq = freq;
            this.order = order;
            this.left = left;
            this.right = right;
        }

        boolean isLeaf() {
            return left == null && right == null;
        }

        @Override
        public int compareTo(Node o) {
            if (freq != o.freq) return Long.compare(freq, o.freq);
            return Integer.compare(order, o.order);
        }
    }

    static Node buildTree(long[] freq) {
        PriorityQueue<Node> pq = new PriorityQueue<>();
        int order = 0;
        for (int i = 0; i < 256; i++) {
            if (freq[i] > 0) pq.add(new Node(i, freq[i], order++, null, null));
        }
        if (pq.isEmpty()) return null;
        if (pq.size() == 1) {                       // file has only one distinct byte
            Node only = pq.poll();
            return new Node(-1, only.freq, order++, only, null);
        }
        while (pq.size() > 1) {
            Node a = pq.poll();
            Node b = pq.poll();
            pq.add(new Node(-1, a.freq + b.freq, order++, a, b));
        }
        return pq.poll();
    }

    static void buildCodes(Node node, String prefix, String[] codes) {
        if (node == null) return;
        if (node.isLeaf()) {
            codes[node.symbol] = prefix.isEmpty() ? "0" : prefix;
            return;
        }
        buildCodes(node.left, prefix + "0", codes);
        buildCodes(node.right, prefix + "1", codes);
    }

    /** Compresses 'in' into 'out'. Returns a text report. */
    static String compress(File in, File out) throws IOException {
        byte[] data = Files.readAllBytes(in.toPath());

        long[] freq = new long[256];
        for (byte b : data) freq[b & 0xFF]++;

        Node root = buildTree(freq);
        String[] codes = new String[256];
        buildCodes(root, "", codes);

        int distinct = 0;
        for (long f : freq) if (f > 0) distinct++;

        try (DataOutputStream dos = new DataOutputStream(
                new BufferedOutputStream(new FileOutputStream(out)))) {
            // Header: original length, number of distinct bytes, then (byte, frequency) pairs
            dos.writeLong(data.length);
            dos.writeShort(distinct);
            for (int i = 0; i < 256; i++) {
                if (freq[i] > 0) {
                    dos.writeByte(i);
                    dos.writeLong(freq[i]);
                }
            }
            // Body: the encoded bits packed into bytes
            int current = 0, bitCount = 0;
            for (byte b : data) {
                String code = codes[b & 0xFF];
                for (int k = 0; k < code.length(); k++) {
                    current = (current << 1) | (code.charAt(k) - '0');
                    bitCount++;
                    if (bitCount == 8) {
                        dos.writeByte(current);
                        current = 0;
                        bitCount = 0;
                    }
                }
            }
            if (bitCount > 0) {                      // pad the last byte with zeros
                dos.writeByte(current << (8 - bitCount));
            }
        }

        long newSize = out.length();
        StringBuilder sb = new StringBuilder();
        sb.append("=== COMPRESSION DONE ===\n");
        sb.append("Input file     : ").append(in.getName()).append("\n");
        sb.append("Output file    : ").append(out.getName()).append("\n");
        sb.append("Original size  : ").append(data.length).append(" bytes\n");
        sb.append("Compressed size: ").append(newSize).append(" bytes (includes header)\n");
        if (data.length > 0) {
            double saved = 100.0 * (1.0 - (double) newSize / data.length);
            sb.append(String.format("Space saved    : %.2f%%%n", saved));
        }
        sb.append("\nSymbol   Freq      Code\n");
        sb.append("---------------------------\n");
        for (int i = 0; i < 256; i++) {
            if (freq[i] > 0) {
                sb.append(String.format("%-8s %-9d %s%n", describe(i), freq[i], codes[i]));
            }
        }
        return sb.toString();
    }

    /** Decompresses 'in' into 'out'. Returns a text report. */
    static String decompress(File in, File out) throws IOException {
        long originalLength;
        long[] freq = new long[256];
        byte[] body;

        try (DataInputStream dis = new DataInputStream(
                new BufferedInputStream(new FileInputStream(in)))) {
            originalLength = dis.readLong();
            int distinct = dis.readUnsignedShort();
            for (int i = 0; i < distinct; i++) {
                int sym = dis.readUnsignedByte();
                freq[sym] = dis.readLong();
            }
            body = dis.readAllBytes();
        }

        Node root = buildTree(freq);
        byte[] result = new byte[(int) originalLength];

        if (root != null) {
            int written = 0;
            Node node = root;
            outer:
            for (byte b : body) {
                for (int bit = 7; bit >= 0; bit--) {
                    int v = (b >> bit) & 1;
                    node = (v == 0) ? node.left : node.right;
                    if (node.isLeaf()) {
                        result[written++] = (byte) node.symbol;
                        node = root;
                        if (written == originalLength) break outer;
                    }
                }
            }
        }
        Files.write(out.toPath(), result);

        return "=== DECOMPRESSION DONE ===\n"
                + "Input file   : " + in.getName() + "\n"
                + "Output file  : " + out.getName() + "\n"
                + "Restored size: " + result.length + " bytes\n";
    }

    static String describe(int b) {
        if (b == ' ') return "' '";
        if (b == '\n') return "\\n";
        if (b == '\r') return "\\r";
        if (b == '\t') return "\\t";
        if (b > 32 && b < 127) return String.valueOf((char) b);
        return String.format("0x%02X", b);
    }

    // ---------------------------------------------------------------
    //  GUI
    // ---------------------------------------------------------------
    private final JTextField pathField = new JTextField();
    private final JTextArea log = new JTextArea();
    private File selected;

    public HuffmanCompressor() {
        super("Huffman File Compressor");
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setSize(700, 520);
        setLocationRelativeTo(null);
        setLayout(new BorderLayout(10, 10));

        // Top: file chooser row
        JPanel top = new JPanel(new BorderLayout(8, 8));
        top.setBorder(BorderFactory.createEmptyBorder(10, 10, 0, 10));
        pathField.setEditable(false);
        JButton browse = new JButton("Browse...");
        top.add(new JLabel("File:"), BorderLayout.WEST);
        top.add(pathField, BorderLayout.CENTER);
        top.add(browse, BorderLayout.EAST);

        // Centre: output area
        log.setEditable(false);
        log.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        log.setText("Choose a file, then click Compress or Decompress.\n");
        JScrollPane scroll = new JScrollPane(log);
        scroll.setBorder(BorderFactory.createEmptyBorder(0, 10, 0, 10));

        // Bottom: action buttons
        JButton compressBtn = new JButton("Compress");
        JButton decompressBtn = new JButton("Decompress");
        JButton clearBtn = new JButton("Clear");
        JPanel bottom = new JPanel(new FlowLayout(FlowLayout.CENTER, 15, 10));
        bottom.add(compressBtn);
        bottom.add(decompressBtn);
        bottom.add(clearBtn);

        add(top, BorderLayout.NORTH);
        add(scroll, BorderLayout.CENTER);
        add(bottom, BorderLayout.SOUTH);

        browse.addActionListener(e -> chooseFile());
        compressBtn.addActionListener(e -> run(true));
        decompressBtn.addActionListener(e -> run(false));
        clearBtn.addActionListener(e -> log.setText(""));
    }

    private void chooseFile() {
        JFileChooser chooser = new JFileChooser();
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            selected = chooser.getSelectedFile();
            pathField.setText(selected.getAbsolutePath());
        }
    }

    private void run(boolean doCompress) {
        if (selected == null || !selected.isFile()) {
            JOptionPane.showMessageDialog(this, "Please choose a file first.",
                    "No file", JOptionPane.WARNING_MESSAGE);
            return;
        }
        try {
            String report;
            if (doCompress) {
                File out = new File(selected.getParentFile(), selected.getName() + ".huff");
                report = compress(selected, out);
            } else {
                File out = restoredName(selected);
                report = decompress(selected, out);
            }
            log.setText(report);
            log.setCaretPosition(0);
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this,
                    "Something went wrong: " + ex.getMessage()
                            + "\n(Is this a valid .huff file?)",
                    "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    /** notes.txt.huff -> notes_restored.txt */
    private static File restoredName(File f) {
        String name = f.getName();
        if (name.endsWith(".huff")) name = name.substring(0, name.length() - 5);
        int dot = name.lastIndexOf('.');
        String restored = (dot > 0)
                ? name.substring(0, dot) + "_restored" + name.substring(dot)
                : name + "_restored";
        return new File(f.getParentFile(), restored);
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new HuffmanCompressor().setVisible(true));
    }
}