import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.security.*;
import java.security.cert.Certificate;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public class Broker {
    private static final int PORTA = 8080;
    private static PrivateKey chavePrivadaBroker;
    private static Certificate certificadoBroker;

    private static ConcurrentHashMap<String, List<TrataCliente>> topicosAtivos = new ConcurrentHashMap<>();
    private static ConcurrentHashMap<TrataCliente, String> nomesConexoes = new ConcurrentHashMap<>();
    private static ConcurrentHashMap<String, HistoricoTopico> dadosTopicos = new ConcurrentHashMap<>();

    public static void main(String[] args) {
        try {
            char[] senha = "123456".toCharArray();
            KeyStore ks = KeyStore.getInstance("JKS");
            try (FileInputStream fis = new FileInputStream("broker.keystore")) {
                ks.load(fis, senha);
            }
            chavePrivadaBroker = (PrivateKey) ks.getKey("broker", senha);
            certificadoBroker = ks.getCertificate("broker");
            System.out.println("[INFO] KeyStore carregado com sucesso.");
        } catch (Exception e) {
            System.err.println("[ERRO CRÍTICO] Falha ao carregar o arquivo 'broker.keystore'!");
            return;
        }

        System.out.println("Broker Seguro iniciado na porta " + PORTA + "...");
        try (ServerSocket serverSocket = new ServerSocket(PORTA)) {
            while (true) {
                Socket socketCliente = serverSocket.accept();
                new Thread(new TrataCliente(socketCliente)).start();
            }
        } catch (IOException e) { e.printStackTrace(); }
    }

    private static class HistoricoTopico {
        private final Set<String> membrosHistoricos = ConcurrentHashMap.newKeySet();
        private final List<MensagemBufferizada> bufferMensagens = new CopyOnWriteArrayList<>();

        public void adicionarMembro(String nome) { membrosHistoricos.add(nome); }
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
        public boolean estaVazio() { return membrosHistoricos.isEmpty() && bufferMensagens.isEmpty(); }
        public boolean contemMembro(String nome) { return membrosHistoricos.contains(nome); }
        private void verificarEExcluirMensagensLidas() { bufferMensagens.removeIf(mb -> mb.usuariosPendentes.isEmpty()); }
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
        private SecretKeySpec chaveAES;

        public TrataCliente(Socket socket) { this.socket = socket; }

        // CORREÇÃO: Envio de canal gera agora um IV dinâmico (SecureRandom) a cada mensagem
        public void enviarMensagemParaCliente(Mensagem msg) throws Exception {
            synchronized (out) {
                if (this.chaveAES != null) {
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

                    Mensagem envelope = new Mensagem(Mensagem.TipoAcao.MENSAGEM_CIFRADA_CANAL, "", "", "Broker");
                    envelope.setDadosCifradosCanal(dadosCifrados);
                    envelope.setIvCanal(ivDinamico); // Anexa o IV ao pacote
                    out.writeUnshared(envelope);
                } else {
                    out.writeUnshared(msg);
                }
                out.flush();
                out.reset();
            }
        }

        @Override
        public void run() {
            try {
                out = new ObjectOutputStream(socket.getOutputStream());
                ObjectInputStream in = new ObjectInputStream(socket.getInputStream());

                while (true) {
                    Mensagem msgEntrada = (Mensagem) in.readObject();
                    if (msgEntrada == null) continue;

                    Mensagem msg = msgEntrada;

                    // CORREÇÃO: Desencriptação utiliza agora o IV dinâmico vindo da mensagem instanciada
                    if (msgEntrada.getAcao() == Mensagem.TipoAcao.MENSAGEM_CIFRADA_CANAL) {
                        if (this.chaveAES == null) {
                            socket.close();
                            return;
                        }
                        Cipher decipherAES = Cipher.getInstance("AES/CBC/PKCS5Padding");
                        decipherAES.init(Cipher.DECRYPT_MODE, this.chaveAES, new IvParameterSpec(msgEntrada.getIvCanal()));
                        byte[] dadosClaros = decipherAES.doFinal(msgEntrada.getDadosCifradosCanal());
                        
                        try (ObjectInputStream ois = new ObjectInputStream(new ByteArrayInputStream(dadosClaros))) {
                            msg = (Mensagem) ois.readObject();
                        }
                    }

                    switch (msg.getAcao()) {
                        case SOLICITAR_CERTIFICADO:
                            synchronized (out) {
                                out.writeObject(certificadoBroker);
                                out.flush();
                                out.reset();
                            }
                            break;

                        case CHAVE_SESSAO:
                            try {
                                byte[] dadosCifrados = msg.getPayloadCifradoPontaAPonta(); 
                                Cipher cipherRSA = Cipher.getInstance("RSA");
                                cipherRSA.init(Cipher.DECRYPT_MODE, chavePrivadaBroker);
                                byte[] dadosDecifrados = cipherRSA.doFinal(dadosCifrados);
                                
                                byte[] chaveBytes = Arrays.copyOfRange(dadosDecifrados, 0, 32);
                                this.chaveAES = new SecretKeySpec(chaveBytes, "AES");
                                
                                enviarMensagemParaCliente(new Mensagem(Mensagem.TipoAcao.ACK, "", "CHAVE_ACEITA", "Broker"));
                            } catch (Exception ex) {
                                socket.close();
                                return;
                            }
                            break;

                        case IDENTIFICAR:
                            this.nomeDoCliente = msg.getRemetente();
                            boolean autenticado = false;
                            try {
                                if (msg.getAssinatura() != null) {
                                    Signature rsa = Signature.getInstance("SHA256withRSA");
                                    rsa.initVerify(certificadoBroker.getPublicKey());
                                    rsa.update(this.nomeDoCliente.getBytes());
                                    autenticado = rsa.verify(msg.getAssinatura());
                                }
                            } catch (Exception ex) { autenticado = false; }

                            if (!autenticado) {
                                try { enviarMensagemParaCliente(new Mensagem(Mensagem.TipoAcao.IDENTIFICAR, "", "ERRO_AUTENTICACAO", "Broker")); } catch (Exception e) {}
                                socket.close();
                                return;
                            }

                            nomesConexoes.put(this, nomeDoCliente);
                            System.out.println("LOGIN AUTENTICADO: " + nomeDoCliente);

                            for (Map.Entry<String, HistoricoTopico> entry : dadosTopicos.entrySet()) {
                                String topico = entry.getKey();
                                HistoricoTopico hist = entry.getValue();
                                if (hist.contemMembro(nomeDoCliente)) {
                                    topicosAtivos.putIfAbsent(topico, new CopyOnWriteArrayList<>());
                                    List<TrataCliente> inscritos = topicosAtivos.get(topico);
                                    if (!inscritos.contains(this)) inscritos.add(this);
                                }
                            }
                            enviarMensagensPendentes();
                            break;

                        case CRIAR_TOPICO:
                            topicosAtivos.putIfAbsent(msg.getTopico(), new CopyOnWriteArrayList<>());
                            dadosTopicos.putIfAbsent(msg.getTopico(), new HistoricoTopico());
                            List<TrataCliente> inscritosCriacao = topicosAtivos.get(msg.getTopico());
                            if (!inscritosCriacao.contains(this)) {
                                inscritosCriacao.add(this);
                                dadosTopicos.get(msg.getTopico()).adicionarMembro(nomeDoCliente);
                            }
                            enviarMensagemParaCliente(new Mensagem(Mensagem.TipoAcao.ACK, msg.getTopico(), "OK", "Broker"));
                            break;

                        case SUBSCRIBE:
                            topicosAtivos.putIfAbsent(msg.getTopico(), new CopyOnWriteArrayList<>());
                            dadosTopicos.putIfAbsent(msg.getTopico(), new HistoricoTopico());
                            List<TrataCliente> inscritosManual = topicosAtivos.get(msg.getTopico());
                            if (!inscritosManual.contains(this)) {
                                inscritosManual.add(this);
                                dadosTopicos.get(msg.getTopico()).adicionarMembro(nomeDoCliente);
                            }
                            enviarMensagemParaCliente(new Mensagem(Mensagem.TipoAcao.ACK, msg.getTopico(), "OK", "Broker"));
                            enviarMensagensPendentesDoTopico(msg.getTopico());
                            break;

                        case UNSUBSCRIBE:
                            enviarMensagensPendentesDoTopico(msg.getTopico());
                            removerClienteDoTopico(msg.getTopico());
                            break;

                        case PUBLISH:
                            List<TrataCliente> alvo = topicosAtivos.get(msg.getTopico());
                            if (alvo == null || !alvo.contains(this)) break;

                            HistoricoTopico historico = dadosTopicos.get(msg.getTopico());
                            if (historico != null) historico.adicionarMensagem(msg);

                            for (TrataCliente clienteTratador : alvo) {
                                try {
                                    clienteTratador.enviarMensagemParaCliente(msg);
                                    String nomeDestinatario = nomesConexoes.get(clienteTratador);
                                    if (nomeDestinatario != null && historico != null) {
                                        historico.confirmarDownload(nomeDestinatario, msg);
                                    }
                                } catch (Exception e) { alvo.remove(clienteTratador); }
                            }
                            break;
                        default: break;
                    }
                }
            } catch (Exception e) {
                nomesConexoes.remove(this);
                for (String t : topicosAtivos.keySet()) {
                    List<TrataCliente> lista = topicosAtivos.get(t);
                    if (lista != null) lista.remove(this);
                }
            }
        }

        private void removerClienteDoTopico(String topico) {
            List<TrataCliente> lista = topicosAtivos.get(topico);
            if (lista != null) {
                lista.remove(this);
                if (lista.isEmpty()) topicosAtivos.remove(topico);
            }
            HistoricoTopico hist = dadosTopicos.get(topico);
            if (hist != null) {
                hist.removerMembro(nomeDoCliente);
                if (hist.estaVazio()) dadosTopicos.remove(topico);
            }
        }

        private void enviarMensagensPendentes() {
            for (String topico : dadosTopicos.keySet()) enviarMensagensPendentesDoTopico(topico);
        }

        private void enviarMensagensPendentesDoTopico(String topico) {
            HistoricoTopico hist = dadosTopicos.get(topico);
            if (hist != null) {
                List<Mensagem> pendentes = new ArrayList<>(hist.obterPendentesDoCliente(nomeDoCliente));
                for (Mensagem m : pendentes) {
                    try {
                        enviarMensagemParaCliente(m);
                        hist.confirmarDownload(nomeDoCliente, m);
                    } catch (Exception e) { break; }
                }
            }
        }
    }
}