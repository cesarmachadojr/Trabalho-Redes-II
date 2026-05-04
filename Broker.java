import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

public class Broker {
    private static final int PORTA = 8080;
    // Mapa: Nome do Tópico -> Lista de canais de saída (clientes inscritos)
    private static ConcurrentHashMap<String, List<ObjectOutputStream>> topicos = new ConcurrentHashMap<>();

    public static void main(String[] args) {
        System.out.println("Broker iniciado na porta " + PORTA + ". Aguardando conexões...");

        try (ServerSocket serverSocket = new ServerSocket(PORTA)) {
            while (true) {
                Socket socketCliente = serverSocket.accept();
                System.out.println("Novo cliente conectado: " + socketCliente.getInetAddress());
                // Inicia uma nova thread para cada cliente
                new Thread(new TrataCliente(socketCliente)).start();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static class TrataCliente implements Runnable {
        private Socket socket;

        public TrataCliente(Socket socket) {
            this.socket = socket;
        }

        @Override
        public void run() {
            try {
                // Importante: Sempre crie o Output antes do Input em Sockets Java
                ObjectOutputStream out = new ObjectOutputStream(socket.getOutputStream());
                ObjectInputStream in = new ObjectInputStream(socket.getInputStream());

                while (true) {
                    Mensagem msg = (Mensagem) in.readObject();

                    switch (msg.getAcao()) {
                        case CRIAR_TOPICO:
                            topicos.putIfAbsent(msg.getTopico(), new CopyOnWriteArrayList<>());
                            System.out.println("Tópico criado: " + msg.getTopico());
                            break;

                        case SUBSCRIBE:
                            // MUDANÇA: Se o tópico não existir, cria automaticamente na hora de inscrever
                            topicos.putIfAbsent(msg.getTopico(), new CopyOnWriteArrayList<>());
                            
                            List<ObjectOutputStream> inscritos = topicos.get(msg.getTopico());
                            // Impede que o cliente seja duplicado na lista
                            if (!inscritos.contains(out)) {
                                inscritos.add(out);
                                System.out.println("Cliente inscrito no tópico: " + msg.getTopico());
                            }
                            break;

                        case UNSUBSCRIBE: // <-- BLOCO NOVO ADICIONADO
                            List<ObjectOutputStream> inscritosParaSair = topicos.get(msg.getTopico());
                            if (inscritosParaSair != null) {
                                // Remove o stream deste cliente da lista de envio
                                inscritosParaSair.remove(out);
                                System.out.println("Cliente desinscrito do tópico: " + msg.getTopico());
                            }
                            break;

                        case PUBLISH:
                            List<ObjectOutputStream> alvo = topicos.get(msg.getTopico());
                            if (alvo != null) {
                                System.out.println("Roteando mensagem para o tópico: " + msg.getTopico() + " (" + alvo.size() + " inscritos)");
                                
                                for (ObjectOutputStream clienteOut : alvo) {
                                    // Try-catch dentro do loop. Se um cliente falhar, não afeta os outros!
                                    try {
                                        synchronized (clienteOut) {
                                            clienteOut.writeUnshared(msg); // writeUnshared ignora o cache do Java
                                            clienteOut.flush();
                                            clienteOut.reset(); // Limpa a memória do stream (Essencial para múltiplos envios)
                                        }
                                    } catch (IOException e) {
                                        // Se deu erro ao enviar, significa que o cliente fechou o terminal. Removemos ele da lista.
                                        System.out.println("Um cliente caiu. Removendo da lista...");
                                        alvo.remove(clienteOut);
                                    }
                                }
                            }
                            break;
                    }
                }
            } catch (EOFException e) {
                System.out.println("Cliente desconectado de forma segura.");
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }
}