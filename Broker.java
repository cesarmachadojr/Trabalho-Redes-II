import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

public class Broker {
    private static final int PORTA = 8080;
    private static ConcurrentHashMap<String, List<ObjectOutputStream>> topicos = new ConcurrentHashMap<>();

    public static void main(String[] args) {
        System.out.println("Broker iniciado na porta " + PORTA + "...");
        try (ServerSocket serverSocket = new ServerSocket(PORTA)) {
            while (true) {
                Socket socketCliente = serverSocket.accept();
                new Thread(new TrataCliente(socketCliente)).start();
            }
        } catch (IOException e) { e.printStackTrace(); }
    }

    private static class TrataCliente implements Runnable {
        private Socket socket;
        private String nomeDoCliente = "Desconhecido";
        private ObjectOutputStream out; // Armazenado para usar na desconexão

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
                            System.out.println("LOGIN: " + nomeDoCliente);
                            break;

                        case CRIAR_TOPICO:
                            topicos.putIfAbsent(msg.getTopico(), new CopyOnWriteArrayList<>());
                            List<ObjectOutputStream> inscritosCriacao = topicos.get(msg.getTopico());
                            if (!inscritosCriacao.contains(out)) {
                                inscritosCriacao.add(out);
                                System.out.println("TOPICO: " + nomeDoCliente + " criou e se inscreveu em: " + msg.getTopico());
                            }
                            break;

                        case SUBSCRIBE:
                            topicos.putIfAbsent(msg.getTopico(), new CopyOnWriteArrayList<>());
                            List<ObjectOutputStream> inscritosManual = topicos.get(msg.getTopico());
                            if (!inscritosManual.contains(out)) {
                                inscritosManual.add(out);
                                System.out.println("SUB: " + nomeDoCliente + " se inscreveu em: " + msg.getTopico());
                            }
                            break;

                        case UNSUBSCRIBE:
                            List<ObjectOutputStream> listaSair = topicos.get(msg.getTopico());
                            if (listaSair != null) {
                                listaSair.remove(out);
                                System.out.println("UNSUB: " + nomeDoCliente + " saiu de: " + msg.getTopico());
                                
                                // ADICIONADO: Remove o tópico se ficar vazio
                                if (listaSair.isEmpty()) {
                                    topicos.remove(msg.getTopico());
                                    System.out.println("TOPICO EXCLUIDO: " + msg.getTopico() + " ficou vazio.");
                                }
                            }
                            break;

                        case PUBLISH:
                            List<ObjectOutputStream> alvo = topicos.get(msg.getTopico());
                            
                            // --- ADICIONADO: SÓ MANDA SE ESTIVER INSCRITO ---
                            if (alvo == null || !alvo.contains(out)) {
                                System.out.println("BLOQUEADO: " + nomeDoCliente + " tentou postar em " + msg.getTopico() + " sem estar inscrito.");
                                break; 
                            }
                            // -----------------------------------------------

                            System.out.println("PUB: " + nomeDoCliente + " postou em " + msg.getTopico());
                            for (ObjectOutputStream clienteOut : alvo) {
                                try {
                                    synchronized (clienteOut) {
                                        clienteOut.writeUnshared(msg);
                                        clienteOut.flush();
                                        clienteOut.reset();
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
                // ADICIONADO: Limpeza automática ao desconectar para evitar tópicos fantasmas
                for (String t : topicos.keySet()) {
                    List<ObjectOutputStream> lista = topicos.get(t);
                    if (lista != null && lista.contains(out)) {
                        lista.remove(out);
                        if (lista.isEmpty()) topicos.remove(t);
                    }
                }
            }
        }
    }
}