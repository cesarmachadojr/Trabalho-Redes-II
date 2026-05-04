import java.io.*;
import java.net.*;
import java.util.Scanner;

public class Cliente {
    private static final String IP_BROKER = "127.0.0.1";
    private static final int PORTA_BROKER = 8080;

    public static void main(String[] args) {
        try (Socket socket = new Socket(IP_BROKER, PORTA_BROKER)) {
            ObjectOutputStream out = new ObjectOutputStream(socket.getOutputStream());
            ObjectInputStream in = new ObjectInputStream(socket.getInputStream());
            Scanner scanner = new Scanner(System.in);

            // Thread para escutar mensagens do Broker em background
            Thread ouvinte = new Thread(() -> {
                try {
                    while (true) {
                        Mensagem msgRecebida = (Mensagem) in.readObject();
                        System.out.println("\n[NOVA MENSAGEM - " + msgRecebida.getTopico() + "]: " + msgRecebida.getPayload());
                        System.out.print("Escolha uma opção (1-Criar, 2-Inscrever, 3-Publicar, 4-Desinscrever): ");
                    }
                } catch (Exception e) {
                    System.out.println("\nConexão com o broker encerrada.");
                }
            });
            ouvinte.start();

            // Loop principal (Menu do usuário)
            while (true) {
                System.out.println("\n--- MENU MQTT ---");
                System.out.println("1. Criar Tópico");
                System.out.println("2. Inscrever-se em um Tópico (Subscribe)");
                System.out.println("3. Publicar em um Tópico (Publish)");
                System.out.println("4. Desinscrever-se (Unsubscribe)"); // <-- Nova opção
                System.out.print("Escolha uma opção: ");
                
                String opcao = scanner.nextLine();
                String topico;

                switch (opcao) {
                    case "1":
                        System.out.print("Digite o nome do novo tópico: ");
                        topico = scanner.nextLine();
                        out.writeObject(new Mensagem(Mensagem.TipoAcao.CRIAR_TOPICO, topico, ""));
                        out.flush();
                        break;
                    case "2":
                        System.out.print("Digite o nome do tópico para se inscrever: ");
                        topico = scanner.nextLine();
                        out.writeObject(new Mensagem(Mensagem.TipoAcao.SUBSCRIBE, topico, ""));
                        out.flush();
                        break;
                    case "3":
                        System.out.print("Digite o nome do tópico alvo: ");
                        topico = scanner.nextLine();
                        System.out.print("Digite a mensagem: ");
                        String payload = scanner.nextLine();
                        out.writeObject(new Mensagem(Mensagem.TipoAcao.PUBLISH, topico, payload));
                        out.flush();
                        break;
                    case "4": // <-- BLOCO NOVO ADICIONADO
                        System.out.print("Digite o nome do tópico para sair: ");
                        topico = scanner.nextLine();
                        out.writeObject(new Mensagem(Mensagem.TipoAcao.UNSUBSCRIBE, topico, ""));
                        out.flush();
                        System.out.println("Solicitação de saída enviada!");
                        break;
                    default:
                        System.out.println("Opção inválida.");
                }
            }
        } catch (IOException e) {
            System.err.println("Erro ao conectar no Broker. Ele está rodando?");
        }
    }
}