import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

public class Broker {
    private static final int PORTA = 8080;
    
    // Armazena as conexões socket ativas na sessão por tópico
    private static ConcurrentHashMap<String, List<ObjectOutputStream>> topicosAtivos = new ConcurrentHashMap<>();
    
    // Mapeamento auxiliar para correlacionar o fluxo de saída com o Nome do Cliente ativo
    private static ConcurrentHashMap<ObjectOutputStream, String> nomesConexoes = new ConcurrentHashMap<>();
    
    // Gerenciador de buffer de mensagens e membros históricos por tópico
    private static ConcurrentHashMap<String, HistoricoTopico> dadosTopicos = new ConcurrentHashMap<>();

    public static void main(String[] args) {
        System.out.println("Broker iniciado na porta " + PORTA + "...");
        try (ServerSocket serverSocket = new ServerSocket(PORTA)) {
            while (true) {
                Socket socketCliente = serverSocket.accept();
                new Thread(new TrataCliente(socketCliente)).start();
            }
        } catch (IOException e) { e.printStackTrace(); }
    }

    // --- CLASSE AUXILIAR: Gerencia a retenção de mensagens por tópico ---
    private static class HistoricoTopico {
        private final Set<String> membrosHistoricos = ConcurrentHashMap.newKeySet();
        private final List<MensagemBufferizada> bufferMensagens = new CopyOnWriteArrayList<>();

        public void adicionarMembro(String nome) {
            membrosHistoricos.add(nome);
        }

        public void removerMembro(String nome) {
            membrosHistoricos.remove(nome);
            verificarEExcluirMensagensLidas();
        }

        public void adicionarMensagem(Mensagem msg) {
            MensagemBufferizada novaMsg = new MensagemBufferizada(msg, new HashSet<>(membrosHistoricos));
            bufferMensagens.add(novaMsg);
        }

        public List<Mensagem> obterPendentesDoCliente(String nomeCliente) {
            List<Mensagem> pendentes = new ArrayList<>();
            for (MensagemBufferizada mb : bufferMensagens) {
                if (mb.mensagemOriginal.getAcao() != null && mb.usuariosPendentes.contains(nomeCliente)) {
                    pendentes.add(mb.mensagemOriginal);
                }
            }
            return pendentes;
        }

        public void confirmarDownload(String nomeCliente, Mensagem msgOriginal) {
            for (MensagemBufferizada mb : bufferMensagens) {
                if (mb.mensagemOriginal == msgOriginal) {
                    mb.usuariosPendentes.remove(nomeCliente);
                }
            }
            verificarEExcluirMensagensLidas();
        }

        private void verificarEExcluirMensagensLidas() {
            bufferMensagens.removeIf(mb -> mb.usuariosPendentes.isEmpty());
        }

        public boolean estaVazio() {
            return membrosHistoricos.isEmpty() && bufferMensagens.isEmpty();
        }
    }

    // --- CORRIGIDO: Atributos declarados explicitamente com escopo correto ---
    private static class MensagemBufferizada {
        public Mensagem mensagemOriginal; // Nome corrigido para bater com o resto do broker
        public Set<String> usuariosPendentes;

        public MensagemBufferizada(Mensagem mensagemOriginal, Set<String> usuariosPendentes) {
            this.mensagemOriginal = mensagemOriginal;
            this.usuariosPendentes = Collections.synchronizedSet(usuariosPendentes);
        }
    }

    private static class TrataCliente implements Runnable {
        private Socket socket;
        private String nomeDoCliente = "Desconhecido";
        private ObjectOutputStream out;

        public TrataCliente(Socket socket) { this.socket = socket; }

        @Override
        public void run() {
            try {
                out = new ObjectOutputStream(socket.getOutputStream());
                ObjectInputStream in = new ObjectInputStream(socket.getInputStream());

                while (true) {
                    Mensagem msg = (Mensagem) in.readObject();

                    switch (msg.getAcao()) {
                        case IDENTIFICAR:
                            this.nomeDoCliente = msg.getRemetente();
                            nomesConexoes.put(out, nomeDoCliente);
                            System.out.println("LOGIN: " + nomeDoCliente);
                            
                            enviarMensagensPendentes();
                            break;

                        case CRIAR_TOPICO:
                            topicosAtivos.putIfAbsent(msg.getTopico(), new CopyOnWriteArrayList<>());
                            dadosTopicos.putIfAbsent(msg.getTopico(), new HistoricoTopico());
                            
                            List<ObjectOutputStream> inscritosCriacao = topicosAtivos.get(msg.getTopico());
                            if (!inscritosCriacao.contains(out)) {
                                inscritosCriacao.add(out);
                                dadosTopicos.get(msg.getTopico()).adicionarMembro(nomeDoCliente);
                                System.out.println("TOPICO: " + nomeDoCliente + " criou e se inscreveu em: " + msg.getTopico());
                            }
                            break;

                        case SUBSCRIBE:
                            topicosAtivos.putIfAbsent(msg.getTopico(), new CopyOnWriteArrayList<>());
                            dadosTopicos.putIfAbsent(msg.getTopico(), new HistoricoTopico());
                            
                            List<ObjectOutputStream> inscritosManual = topicosAtivos.get(msg.getTopico());
                            if (!inscritosManual.contains(out)) {
                                inscritosManual.add(out);
                                dadosTopicos.get(msg.getTopico()).adicionarMembro(nomeDoCliente);
                                System.out.println("SUB: " + nomeDoCliente + " se inscreveu em: " + msg.getTopico());
                                
                                enviarMensagensPendentesDoTopico(msg.getTopico());
                            }
                            break;

                        case UNSUBSCRIBE:
                            removerClienteDoTopico(msg.getTopico());
                            break;

                        case PUBLISH:
                            List<ObjectOutputStream> alvo = topicosAtivos.get(msg.getTopico());
                            
                            if (alvo == null || !alvo.contains(out)) {
                                System.out.println("BLOQUEADO: " + nomeDoCliente + " tentou postar em " + msg.getTopico() + " sem estar inscrito.");
                                break; 
                            }

                            System.out.println("PUB: " + nomeDoCliente + " postou em " + msg.getTopico());
                            
                            HistoricoTopico historico = dadosTopicos.get(msg.getTopico());
                            if (historico != null) {
                                historico.adicionarMensagem(msg);
                            }

                            for (ObjectOutputStream clienteOut : alvo) {
                                try {
                                    synchronized (clienteOut) {
                                        clienteOut.writeUnshared(msg);
                                        clienteOut.flush();
                                        clienteOut.reset();
                                    }
                                    
                                    String nomeDestinatario = nomesConexoes.get(clienteOut);
                                    if (nomeDestinatario != null && historico != null) {
                                        historico.confirmarDownload(nomeDestinatario, msg);
                                    }
                                } catch (IOException e) { 
                                    alvo.remove(clienteOut); 
                                }
                            }
                            break;
                    }
                }
            } catch (Exception e) {
                System.out.println("Conexão encerrada: " + nomeDoCliente);
                nomesConexoes.remove(out);
                for (String t : topicosAtivos.keySet()) {
                    List<ObjectOutputStream> lista = topicosAtivos.get(t);
                    if (lista != null && lista.contains(out)) {
                        lista.remove(out);
                    }
                }
            }
        }

        private void removerClienteDoTopico(String topico) {
            List<ObjectOutputStream> lista = topicosAtivos.get(topico);
            if (lista != null) {
                lista.remove(out);
                System.out.println("UNSUB: " + nomeDoCliente + " saiu de: " + topico);
                if (lista.isEmpty()) topicosAtivos.remove(topico);
            }
            
            HistoricoTopico hist = dadosTopicos.get(topico);
            if (hist != null) {
                hist.removerMembro(nomeDoCliente);
                if (hist.estaVazio()) {
                    dadosTopicos.remove(topico);
                    System.out.println("TOPICO EXCLUIDO: " + topico + " ficou sem membros e sem mensagens.");
                }
            }
        }

        private void enviarMensagensPendentes() {
            for (String topico : dadosTopicos.keySet()) {
                enviarMensagensPendentesDoTopico(topico);
            }
        }

        private void enviarMensagensPendentesDoTopico(String topico) {
            HistoricoTopico hist = dadosTopicos.get(topico);
            if (hist != null) {
                List<Mensagem> pendentes = hist.obterPendentesDoCliente(nomeDoCliente);
                if (!pendentes.isEmpty()) {
                    System.out.println("HISTORICO: Enviando " + pendentes.size() + " mensagens retidas em [" + topico + "] para " + nomeDoCliente);
                    for (Mensagem m : pendentes) {
                        try {
                            synchronized (out) {
                                out.writeUnshared(m);
                                out.flush();
                                out.reset();
                            }
                            hist.confirmarDownload(nomeDoCliente, m);
                        } catch (IOException e) {
                            break; 
                        }
                    }
                }
            }
        }
    }
}