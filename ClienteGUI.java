import java.awt.*;
import java.io.*;
import java.net.*;
import javax.swing.*;

public class ClienteGUI extends JFrame {
    private static final String IP_BROKER = "127.0.0.1";
    private static final int PORTA_BROKER = 8080;

    private Socket socket;
    private ObjectOutputStream out;
    private ObjectInputStream in;
    private String meuNome;

    // Componentes da Interface Gráfica
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
        super("IFSC - Sistema de Mensagens MQTT");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(800, 500);
        setLocationRelativeTo(null);
        setLayout(new BorderLayout(10, 10));

        // 1. Painel Superior: Autenticação / Nome do Usuário
        JPanel painelSuperior = new JPanel(new FlowLayout(FlowLayout.LEFT));
        painelSuperior.setBorder(BorderFactory.createTitledBorder("Configuração de Identidade"));
        painelSuperior.add(new JLabel("Nome de Usuário:"));
        txtNome = new JTextField(15);
        painelSuperior.add(txtNome);
        btnConectar = new JButton("Conectar ao Broker");
        painelSuperior.add(btnConectar);
        add(painelSuperior, BorderLayout.NORTH);

        // 2. Painel Esquerdo: Gerenciamento de Múltiplos Tópicos
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

        // 3. Painel Central: Chat e Envio de Mensagens
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

        // Bloquear componentes até conectar
        alternarComponentes(false);

        // --- Listeners ---

        btnConectar.addActionListener(e -> conectarAoBroker());

        // --- CORREÇÃO 1: Não adiciona na lista aqui; aguarda ACK do broker (tratado no OuvinteServidor) ---
        btnCriarTopico.addActionListener(e -> enviarComando(Mensagem.TipoAcao.CRIAR_TOPICO, txtTopico.getText().trim(), ""));
        btnInscrever.addActionListener(e -> enviarComando(Mensagem.TipoAcao.SUBSCRIBE, txtTopico.getText().trim(), ""));

        btnSairTopico.addActionListener(e -> {
            String selecionado = listTopicos.getSelectedValue();
            if (selecionado != null) {
                enviarComando(Mensagem.TipoAcao.UNSUBSCRIBE, selecionado, "");
                // Remoção local imediata é segura aqui pois o broker entregará pendentes antes de remover
                modelTopicos.removeElement(selecionado);
            } else {
                JOptionPane.showMessageDialog(this, "Selecione um tópico na lista para sair.");
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

    private void conectarAoBroker() {
        meuNome = txtNome.getText().trim();
        if (meuNome.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Por favor, digite um nome de usuário válido.");
            return;
        }

        // --- AUTENTICAÇÃO: Carrega o arquivo de assinatura digital offline do cliente ---
        byte[] minhaAssinatura = null;
        try (ObjectInputStream ois = new ObjectInputStream(new FileInputStream(meuNome + ".assinatura"))) {
            minhaAssinatura = (byte[]) ois.readObject();
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, "Erro de Autenticação: Arquivo '" + meuNome + ".assinatura' não encontrado!\n" +
                    "Certifique-se de gerar as credenciais no Processo Offline primeiro.");
            return;
        }

        try {
            socket = new Socket(IP_BROKER, PORTA_BROKER);
            out = new ObjectOutputStream(socket.getOutputStream());
            in = new ObjectInputStream(socket.getInputStream());

            // Envia identificação com assinatura digital
            out.writeObject(new Mensagem(Mensagem.TipoAcao.IDENTIFICAR, "", "", meuNome, minhaAssinatura));
            out.flush();

            alternarComponentes(true);
            modelTopicos.clear(); // Limpa lista local; o broker vai reenviar ACKs dos tópicos ativos
            areaChat.append("System: Conectado com sucesso como [" + meuNome + "]\n");

            // Inicia thread ouvinte do Broker
            new Thread(new OuvinteServidor()).start();

        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this, "Erro ao conectar no Broker (Verifique se ele está rodando).");
        }
    }

    // --- CORREÇÃO 1: Removido o parâmetro adicionarNaLista; adição ocorre somente via ACK ---
    private void enviarComando(Mensagem.TipoAcao acao, String topico, String payload) {
        if (topico.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Preencha o nome do tópico.");
            return;
        }
        try {
            out.writeObject(new Mensagem(acao, topico, payload, meuNome));
            out.flush();
            txtTopico.setText("");
        } catch (IOException ex) {
            areaChat.append("System: Falha ao enviar comando para o broker.\n");
        }
    }

    private void enviarMensagemPublicacao() {
        String topicoSelecionado = listTopicos.getSelectedValue();
        String texto = txtMensagem.getText().trim();

        if (topicoSelecionado == null) {
            JOptionPane.showMessageDialog(this, "Selecione na lista lateral esquerda qual o tópico alvo da mensagem.");
            return;
        }
        if (texto.isEmpty()) return;

        try {
            out.writeObject(new Mensagem(Mensagem.TipoAcao.PUBLISH, topicoSelecionado, texto, meuNome));
            out.flush();
            txtMensagem.setText("");
        } catch (IOException ex) {
            areaChat.append("System: Falha ao publicar mensagem.\n");
        }
    }

    // Thread que escuta o servidor continuamente sem travar a janela
    private class OuvinteServidor implements Runnable {
        @Override
        public void run() {
            try {
                while (true) {
                    Mensagem msg = (Mensagem) in.readObject();

                    // --- AUTENTICAÇÃO: Trata recusa de conexão ---
                    if ("ERRO_AUTENTICACAO".equals(msg.getPayload())) {
                        SwingUtilities.invokeLater(() -> {
                            JOptionPane.showMessageDialog(ClienteGUI.this,
                                "O Servidor recusou o login: Assinatura digital inválida ou corrompida!",
                                "Erro de Segurança", JOptionPane.ERROR_MESSAGE);
                            alternarComponentes(false);
                            modelTopicos.clear();
                        });
                        break;
                    }

                    // --- CORREÇÃO 1: Trata ACK do broker para adicionar tópico na lista apenas após confirmação ---
                    if (msg.getAcao() == Mensagem.TipoAcao.ACK) {
                        if ("OK".equals(msg.getPayload())) {
                            String topico = msg.getTopico();
                            SwingUtilities.invokeLater(() -> {
                                if (!modelTopicos.contains(topico)) {
                                    modelTopicos.addElement(topico);
                                }
                                areaChat.append("System: Inscrição confirmada no tópico [" + topico + "]\n");
                            });
                        }
                        continue; // ACK processado, não exibe como mensagem de chat
                    }

                    // Exibe mensagem de chat com cliente e tópico de origem
                    String formatada = String.format("[%s - %s]: %s\n",
                            msg.getRemetente(),
                            msg.getTopico(),
                            msg.getPayload()
                    );
                    SwingUtilities.invokeLater(() -> areaChat.append(formatada));
                }
            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> {
                    areaChat.append("System: Conexão com o Broker perdida.\n");
                    alternarComponentes(false);
                });
            }
        }
    }

    public static void main(String[] args) {
        try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); } catch (Exception e) {}
        SwingUtilities.invokeLater(() -> new ClienteGUI().setVisible(true));
    }
}
