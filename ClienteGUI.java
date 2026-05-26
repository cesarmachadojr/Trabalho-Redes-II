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

        // 3. Painel Central/Direito: Visualização do Chat e Envio de Mensagens
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

        // Bloquear componentes até que o usuário se conecte
        alternarComponentes(false);

        // --- Configuração dos Listeners / Eventos de Clique ---
        
        // Ação: Conectar ao Broker
        btnConectar.addActionListener(e -> conectarAoBroker());

        // Ação: Criar Tópico
        btnCriarTopico.addActionListener(e -> enviarComando(Mensagem.TipoAcao.CRIAR_TOPICO, txtTopico.getText().trim(), "", true));

        // Ação: Se inscrever
        btnInscrever.addActionListener(e -> enviarComando(Mensagem.TipoAcao.SUBSCRIBE, txtTopico.getText().trim(), "", true));

        // Ação: Sair de um tópico
        btnSairTopico.addActionListener(e -> {
            String selecionado = listTopicos.getSelectedValue();
            if (selecionado != null) {
                enviarComando(Mensagem.TipoAcao.UNSUBSCRIBE, selecionado, "", false);
                modelTopicos.removeElement(selecionado);
            } else {
                JOptionPane.showMessageDialog(this, "Selecione um tópico na lista para sair.");
            }
        });

        // Ação: Enviar Mensagem (Publish)
        btnEnviar.addActionListener(e -> enviarMensagemPublicacao());
        txtMensagem.addActionListener(e -> enviarMensagemPublicacao()); // Envia também ao apertar Enter
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

        try {
            socket = new Socket(IP_BROKER, PORTA_BROKER);
            out = new ObjectOutputStream(socket.getOutputStream());
            in = new ObjectInputStream(socket.getInputStream());

            // Envia comando de identificação inicial
            out.writeObject(new Mensagem(Mensagem.TipoAcao.IDENTIFICAR, "", "", meuNome));
            out.flush();

            alternarComponentes(true);
            areaChat.append("System: Conectado com sucesso como [" + meuNome + "]\n");

            // Inicia a thread ouvinte do Broker
            new Thread(new OuvinteServidor()).start();

        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this, "Erro ao conectar no Broker (Verifique se ele está rodando).");
        }
    }

    private void enviarComando(Mensagem.TipoAcao acao, String topico, String payload, boolean adicionarNaLista) {
        if (topico.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Preencha o nome do tópico.");
            return;
        }
        try {
            out.writeObject(new Mensagem(acao, topico, payload, meuNome));
            out.flush();
            
            if (adicionarNaLista && !modelTopicos.contains(topico)) {
                modelTopicos.addElement(topico);
            }
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

    // Thread que fica escutando o servidor continuamente sem travar a janela
    private class OuvinteServidor implements Runnable {
        @Override
        public void run() {
            try {
                while (true) {
                    Mensagem msg = (Mensagem) in.readObject();
                    
                    // Exigência do PDF: Indicar claramente o cliente e o tópico de origem
                    String formatada = String.format("[%s - %s]: %s\n", 
                            msg.getRemetente(), 
                            msg.getTopico(), 
                            msg.getPayload()
                    );
                    
                    // Adiciona na interface gráfica de forma segura para threads
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
        // Inicializa o LookAndFeel nativo do sistema operacional para ficar bonito
        try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); } catch (Exception e) {}
        
        SwingUtilities.invokeLater(() -> {
            new ClienteGUI().setVisible(true);
        });
    }
}