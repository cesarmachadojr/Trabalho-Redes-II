import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.security.*;

public class Broker {
    private static final int PORTA = 8080;

    // --- AUTENTICAÇÃO: Chave pública do servidor carregada do arquivo ---
    private static PublicKey chavePublicaServidor;

    // Conexões socket ativas por tópico
    private static ConcurrentHashMap<String, List<ObjectOutputStream>> topicosAtivos = new ConcurrentHashMap<>();

    // Correlaciona fluxo de saída com o nome do cliente
    private static ConcurrentHashMap<ObjectOutputStream, String> nomesConexoes = new ConcurrentHashMap<>();

    // Buffer de mensagens e membros históricos por tópico
    private static ConcurrentHashMap<String, HistoricoTopico> dadosTopicos = new ConcurrentHashMap<>();

    public static void main(String[] args) {
        // --- AUTENTICAÇÃO: Carrega a chave pública antes de iniciar o servidor ---
        try (ObjectInputStream in = new ObjectInputStream(new FileInputStream("servidor.pub"))) {
            chavePublicaServidor = (PublicKey) in.readObject();
            System.out.println("Chave Publica carregada com sucesso. Autenticacao ativada.");
        } catch (Exception e) {
            System.err.println("Erro critico: Arquivo 'servidor.pub' nao encontrado!");
            System.err.println("Por favor, rode o ProcessoOffline primeiro para gerar as chaves.");
            return;
        }

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

        // --- RECONEXÃO: Verifica se o cliente ainda é membro histórico do tópico ---
        public boolean contemMembro(String nome) {
            return membrosHistoricos.contains(nome);
        }
    }

    private static class MensagemBufferizada {
        public Mensagem mensagemOriginal;
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

                            // --- AUTENTICAÇÃO: Verificação da assinatura digital RSA ---
                            boolean autenticado = false;
                            try {
                                if (msg.getAssinatura() != null) {
                                    Signature rsa = Signature.getInstance("SHA256withRSA");
                                    rsa.initVerify(chavePublicaServidor);
                                    rsa.update(this.nomeDoCliente.getBytes());
                                    autenticado = rsa.verify(msg.getAssinatura());
                                }
                            } catch (Exception ex) {
                                autenticado = false;
                            }

                            if (!autenticado) {
                                System.out.println("ALERTA DE SEGURANCA: Conexao recusada para '" + this.nomeDoCliente + "'. Assinatura digital invalida!");
                                try {
                                    out.writeObject(new Mensagem(Mensagem.TipoAcao.IDENTIFICAR, "", "ERRO_AUTENTICACAO", "Broker"));
                                    out.flush();
                                } catch (IOException e) {}
                                socket.close();
                                return;
                            }

                            nomesConexoes.put(out, nomeDoCliente);
                            System.out.println("LOGIN AUTENTICADO: " + nomeDoCliente);

                            // --- RECONEXÃO: Reinserção automática nos tópicos onde o cliente já era membro ---
                            // O HistoricoTopico mantém membrosHistoricos mesmo após desconexão,
                            // então usamos isso para detectar os tópicos do cliente e reinscrever.
                            for (Map.Entry<String, HistoricoTopico> entry : dadosTopicos.entrySet()) {
                                String topico = entry.getKey();
                                HistoricoTopico hist = entry.getValue();

                                if (hist.contemMembro(nomeDoCliente)) {
                                    // Garante que o tópico existe em topicosAtivos
                                    topicosAtivos.putIfAbsent(topico, new CopyOnWriteArrayList<>());
                                    List<ObjectOutputStream> inscritos = topicosAtivos.get(topico);

                                    // Reinserção apenas se não estiver já inscrito (evita duplicata)
                                    if (!inscritos.contains(out)) {
                                        inscritos.add(out);
                                        System.out.println("RECONEXAO: " + nomeDoCliente + " reinscrito automaticamente em: " + topico);
                                    }

                                    // Envia ACK para o cliente repovoar a lista de tópicos na GUI
                                    try {
                                        synchronized (out) {
                                            out.writeUnshared(new Mensagem(Mensagem.TipoAcao.ACK, topico, "OK", "Broker"));
                                            out.flush();
                                            out.reset();
                                        }
                                    } catch (IOException e) { /* ignora */ }
                                }
                            }
                            // ---------------------------------------------------------------------------------

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

                            // --- CORREÇÃO 1: Envia ACK de confirmação ao cliente ---
                            try {
                                synchronized (out) {
                                    out.writeUnshared(new Mensagem(Mensagem.TipoAcao.ACK, msg.getTopico(), "OK", "Broker"));
                                    out.flush();
                                    out.reset();
                                }
                            } catch (IOException e) { /* ignora falha no ACK */ }
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

                            // --- CORREÇÃO 1: Envia ACK de confirmação ao cliente ---
                            try {
                                synchronized (out) {
                                    out.writeUnshared(new Mensagem(Mensagem.TipoAcao.ACK, msg.getTopico(), "OK", "Broker"));
                                    out.flush();
                                    out.reset();
                                }
                            } catch (IOException e) { /* ignora falha no ACK */ }
                            break;

                        case UNSUBSCRIBE:
                            // --- CORREÇÃO 2: Entrega mensagens pendentes ANTES de remover o membro do histórico ---
                            enviarMensagensPendentesDoTopico(msg.getTopico());
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
