import java.awt.*;
import java.io.*;
import java.net.*;
import javax.swing.*;
import java.security.*;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public class ClienteGUI extends JFrame {
    private static final String IP_BROKER = "127.0.0.1";
    private static final int PORTA_BROKER = 8080;

    private Socket socket;
    private ObjectOutputStream out;
    private ObjectInputStream in;
    private String meuNome;

    private SecretKey chaveAES;
    private boolean canalCifradoAtivo = false;

    private JTextField txtNome;
    private JButton btnConectar;
    private JTextField txtTopico;
    private JButton btnCriarTopico;
    private JButton btnInscrever;
    private JButton btnSairTopico;
    private JList<String> listTopicos;
    private DefaultListModel<String> modelTopicos;
    private JTextArea areaChat;
    private JTextField txtMensagem;
    private JButton btnEnviar;

    public ClienteGUI() {
        super("IFSC - Sistema de Mensagens MQTT Seguro");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(800, 500);
        setLocationRelativeTo(null);
        setLayout(new BorderLayout(10, 10));

        JPanel painelSuperior = new JPanel(new FlowLayout(FlowLayout.LEFT));
        painelSuperior.setBorder(BorderFactory.createTitledBorder("Configuração de Identidade"));
        painelSuperior.add(new JLabel("Nome de Usuário:"));
        txtNome = new JTextField(15);
        painelSuperior.add(txtNome);
        btnConectar = new JButton("Conectar ao Broker");
        painelSuperior.add(btnConectar);
        add(painelSuperior, BorderLayout.NORTH);

        JPanel painelEsquerdo = new JPanel(new BorderLayout(5, 5));
        painelEsquerdo.setBorder(BorderFactory.createTitledBorder("Seus Tópicos"));
        painelEsquerdo.setPreferredSize(new Dimension(250, 0));

        JPanel painelAcoesTopico = new JPanel(new GridLayout(4, 1, 5, 5));
        txtTopico = new JTextField();
        txtTopico.setBorder(BorderFactory.createTitledBorder("Nome do Tópico"));
        painelAcoesTopico.add(txtTopico);

        btnCriarTopico = new JButton("Criar Tópico");
        btnInscrever = new JButton("Inscrever-se (Subscribe)");
        btnSairTopico = new JButton("Desinscrever-se (Unsubscribe)");

        painelAcoesTopico.add(btnCriarTopico);
        painelAcoesTopico.add(btnInscrever);
        painelAcoesTopico.add(btnSairTopico);
        painelEsquerdo.add(painelAcoesTopico, BorderLayout.NORTH);

        modelTopicos = new DefaultListModel<>();
        listTopicos = new JList<>(modelTopicos);
        listTopicos.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        JScrollPane scrollTopicos = new JScrollPane(listTopicos);
        scrollTopicos.setBorder(BorderFactory.createTitledBorder("Tópicos Ativos"));
        painelEsquerdo.add(scrollTopicos, BorderLayout.CENTER);
        add(painelEsquerdo, BorderLayout.WEST);

        JPanel painelCentral = new JPanel(new BorderLayout(5, 5));
        painelCentral.setBorder(BorderFactory.createTitledBorder("Painel de Mensagens"));
        areaChat = new JTextArea();
        areaChat.setEditable(false);
        areaChat.setFont(new Font("Monospaced", Font.PLAIN, 12));
        painelCentral.add(new JScrollPane(areaChat), BorderLayout.CENTER);

        JPanel painelEnvio = new JPanel(new BorderLayout(5, 5));
        txtMensagem = new JTextField();
        btnEnviar = new JButton("Enviar");
        painelEnvio.add(txtMensagem, BorderLayout.CENTER);
        painelEnvio.add(btnEnviar, BorderLayout.EAST);
        painelCentral.add(painelEnvio, BorderLayout.SOUTH);
        add(painelCentral, BorderLayout.CENTER);

        alternarComponentes(false);

        btnConectar.addActionListener(e -> conectarAoBroker());
        btnCriarTopico.addActionListener(e -> enviarComando(Mensagem.TipoAcao.CRIAR_TOPICO, txtTopico.getText().trim(), ""));
        btnInscrever.addActionListener(e -> enviarComando(Mensagem.TipoAcao.SUBSCRIBE, txtTopico.getText().trim(), ""));

        btnSairTopico.addActionListener(e -> {
            String selecionado = listTopicos.getSelectedValue();
            if (selecionado != null) {
                enviarComando(Mensagem.TipoAcao.UNSUBSCRIBE, selecionado, "");
                modelTopicos.removeElement(selecionado);
            }
        });

        btnEnviar.addActionListener(e -> enviarMensagemPublicacao());
        txtMensagem.addActionListener(e -> enviarMensagemPublicacao());
    }

    private void alternarComponentes(boolean conectado) {
        txtNome.setEnabled(!conectado);
        btnConectar.setEnabled(!conectado);
        txtTopico.setEnabled(conectado);
        btnCriarTopico.setEnabled(conectado);
        btnInscrever.setEnabled(conectado);
        btnSairTopico.setEnabled(conectado);
        listTopicos.setEnabled(conectado);
        txtMensagem.setEnabled(conectado);
        btnEnviar.setEnabled(conectado);
    }

    // CORREÇÃO: Aplica agora um IV dinâmico e aleatório por transmissão na Camada de Canal
    private void transmitirMensagemNoSocket(Mensagem msg) throws Exception {
        synchronized (out) {
            if (this.canalCifradoAtivo) {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                try (ObjectOutputStream oos = new ObjectOutputStream(baos)) {
                    oos.writeObject(msg);
                }
                byte[] dadosClaros = baos.toByteArray();

                byte[] ivDinamico = new byte[16];
                new SecureRandom().nextBytes(ivDinamico);

                Cipher cipherAES = Cipher.getInstance("AES/CBC/PKCS5Padding");
                cipherAES.init(Cipher.ENCRYPT_MODE, this.chaveAES, new IvParameterSpec(ivDinamico));
                byte[] dadosCifrados = cipherAES.doFinal(dadosClaros);

                Mensagem envelope = new Mensagem(Mensagem.TipoAcao.MENSAGEM_CIFRADA_CANAL, "", "", meuNome);
                envelope.setDadosCifradosCanal(dadosCifrados);
                envelope.setIvCanal(ivDinamico); // Encaminha o IV dinâmico no payload DTO
                out.writeUnshared(envelope);
            } else {
                out.writeUnshared(msg);
            }
            out.flush();
            out.reset();
        }
    }

    private void conectarAoBroker() {
        meuNome = txtNome.getText().trim();
        if (meuNome.isEmpty()) return;

        byte[] minhaAssinatura = null;
        try (ObjectInputStream ois = new ObjectInputStream(new FileInputStream(meuNome + ".assinatura"))) {
            minhaAssinatura = (byte[]) ois.readObject();
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, "Assinatura local não encontrada!");
            return;
        }

        try {
            socket = new Socket(IP_BROKER, PORTA_BROKER);
            out = new ObjectOutputStream(socket.getOutputStream());
            in = new ObjectInputStream(socket.getInputStream());
            this.canalCifradoAtivo = false;

            transmitirMensagemNoSocket(new Mensagem(Mensagem.TipoAcao.SOLICITAR_CERTIFICADO, "", "", meuNome));
            java.security.cert.Certificate certificadoBroker = (java.security.cert.Certificate) in.readObject();

            File arquivoCA = new File("ca.crt");
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            X509Certificate certificadoCA;
            try (FileInputStream fis = new FileInputStream(arquivoCA)) {
                certificadoCA = (X509Certificate) cf.generateCertificate(fis);
            }

            try {
                certificadoBroker.verify(certificadoCA.getPublicKey());
                areaChat.append("System: Certificado do Broker VALIDADO.\n");
            } catch (Exception ex) {
                socket.close();
                return;
            }

            KeyGenerator keyGen = KeyGenerator.getInstance("AES");
            keyGen.init(256); 
            this.chaveAES = keyGen.generateKey();
            byte[] chaveBytes = chaveAES.getEncoded();

            Cipher cipherRSA = Cipher.getInstance("RSA");
            cipherRSA.init(Cipher.ENCRYPT_MODE, certificadoBroker.getPublicKey());
            byte[] envelopeDigital = cipherRSA.doFinal(chaveBytes);

            Mensagem msgEnvelope = new Mensagem(Mensagem.TipoAcao.CHAVE_SESSAO, "", "", meuNome);
            msgEnvelope.setPayloadCifradoPontaAPonta(envelopeDigital);
            
            out.writeUnshared(msgEnvelope);
            out.flush();
            out.reset();

            this.canalCifradoAtivo = true;

            Mensagem ackChave = (Mensagem) in.readObject();
            if (ackChave.getAcao() == Mensagem.TipoAcao.MENSAGEM_CIFRADA_CANAL) {
                Cipher decipherAES = Cipher.getInstance("AES/CBC/PKCS5Padding");
                decipherAES.init(Cipher.DECRYPT_MODE, this.chaveAES, new IvParameterSpec(ackChave.getIvCanal()));
                byte[] dadosClaros = decipherAES.doFinal(ackChave.getDadosCifradosCanal());
                try (ObjectInputStream ois = new ObjectInputStream(new ByteArrayInputStream(dadosClaros))) {
                    ackChave = (Mensagem) ois.readObject();
                }
            }

            areaChat.append("System: Canal Simétrico AES-256 estabelecido.\n");
            transmitirMensagemNoSocket(new Mensagem(Mensagem.TipoAcao.IDENTIFICAR, "", "", meuNome, minhaAssinatura));

            alternarComponentes(true);
            modelTopicos.clear();
            new Thread(new OuvinteServidor()).start();

        } catch (Exception ex) { ex.printStackTrace(); }
    }

    private void enviarComando(Mensagem.TipoAcao acao, String topico, String payload) {
        if (topico.isEmpty()) return;
        try {
            transmitirMensagemNoSocket(new Mensagem(acao, topico, payload, meuNome));
            txtTopico.setText("");
        } catch (Exception ex) { ex.printStackTrace(); }
    }

    private void enviarMensagemPublicacao() {
        String topicoSelecionado = listTopicos.getSelectedValue();
        String texto = txtMensagem.getText().trim();

        if (topicoSelecionado == null || texto.isEmpty()) return;

        try {
            byte[] chaveTopicoBytes = new byte[16];
            byte[] topicoBytes = topicoSelecionado.getBytes("UTF-8");
            System.arraycopy(topicoBytes, 0, chaveTopicoBytes, 0, Math.min(topicoBytes.length, 16));
            SecretKeySpec chaveCompartilhadaTopico = new SecretKeySpec(chaveTopicoBytes, "AES");

            byte[] ivEstaticoPontaAPonta = new byte[16]; // Mantido estático apenas para o ponta-a-ponta simplificado por tópico
            IvParameterSpec ivSpecPontaAPonta = new IvParameterSpec(ivEstaticoPontaAPonta);

            Cipher cipherAES = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipherAES.init(Cipher.ENCRYPT_MODE, chaveCompartilhadaTopico, ivSpecPontaAPonta);
            byte[] textoCifradoBytes = cipherAES.doFinal(texto.getBytes("UTF-8"));

            Mensagem msgPublish = new Mensagem(Mensagem.TipoAcao.PUBLISH, topicoSelecionado, "[CONTEÚDO PROTEGIDO]", meuNome);
            msgPublish.setPayloadCifradoPontaAPonta(textoCifradoBytes);

            transmitirMensagemNoSocket(msgPublish);
            txtMensagem.setText("");
        } catch (Exception ex) { ex.printStackTrace(); }
    }

    private class OuvinteServidor implements Runnable {
        @Override
        public void run() {
            try {
                while (true) {
                    Mensagem msgEntrada = (Mensagem) in.readObject();
                    Mensagem msg = msgEntrada;

                    // CORREÇÃO: Lê o IV dinâmico que o Broker colocou nesta mensagem específica
                    if (msgEntrada.getAcao() == Mensagem.TipoAcao.MENSAGEM_CIFRADA_CANAL) {
                        Cipher decipherAES = Cipher.getInstance("AES/CBC/PKCS5Padding");
                        decipherAES.init(Cipher.DECRYPT_MODE, chaveAES, new IvParameterSpec(msgEntrada.getIvCanal()));
                        byte[] dadosClaros = decipherAES.doFinal(msgEntrada.getDadosCifradosCanal());
                        try (ObjectInputStream ois = new ObjectInputStream(new ByteArrayInputStream(dadosClaros))) {
                            msg = (Mensagem) ois.readObject();
                        }
                    }

                    if ("ERRO_AUTENTICACAO".equals(msg.getPayload())) {
                        alternarComponentes(false);
                        break;
                    }

                    if (msg.getAcao() == Mensagem.TipoAcao.ACK) {
                        if ("OK".equals(msg.getPayload())) {
                            String topico = msg.getTopico();
                            SwingUtilities.invokeLater(() -> {
                                if (!modelTopicos.contains(topico)) modelTopicos.addElement(topico);
                            });
                        }
                        continue;
                    }

                    String payloadTextoExibicao = msg.getPayload();
                    if (msg.getAcao() == Mensagem.TipoAcao.PUBLISH && msg.getPayloadCifradoPontaAPonta() != null) {
                        try {
                            String topicoMensagem = msg.getTopico();
                            byte[] chaveTopicoBytes = new byte[16];
                            byte[] topicoBytes = topicoMensagem.getBytes("UTF-8");
                            System.arraycopy(topicoBytes, 0, chaveTopicoBytes, 0, Math.min(topicoBytes.length, 16));
                            SecretKeySpec chaveCompartilhadaTopico = new SecretKeySpec(chaveTopicoBytes, "AES");

                            byte[] ivEstaticoPontaAPonta = new byte[16];
                            IvParameterSpec ivSpecPontaAPonta = new IvParameterSpec(ivEstaticoPontaAPonta);

                            Cipher decipherAES = Cipher.getInstance("AES/CBC/PKCS5Padding");
                            decipherAES.init(Cipher.DECRYPT_MODE, chaveCompartilhadaTopico, ivSpecPontaAPonta);
                            
                            byte[] textoDecifradoBytes = decipherAES.doFinal(msg.getPayloadCifradoPontaAPonta());
                            payloadTextoExibicao = new String(textoDecifradoBytes, "UTF-8");
                        } catch (Exception ex) {
                            payloadTextoExibicao = "[Erro ao decifrar conteúdo ponta-a-ponta]";
                        }
                    }

                    final String msgFinal = payloadTextoExibicao;
                    final String exibir = String.format("[%s - %s]: %s\n", msg.getRemetente(), msg.getTopico(), msgFinal);
                    SwingUtilities.invokeLater(() -> areaChat.append(exibir));
                }
            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> alternarComponentes(false));
            }
        }
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new ClienteGUI().setVisible(true));
    }
}