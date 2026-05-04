import java.io.*;
import java.net.*;
import java.util.Scanner;

public class Cliente {
    private static final String IP_BROKER = "127.0.0.1";
    private static final int PORTA_BROKER = 8080;

    public static void main(String[] args) {
        Scanner scanner = new Scanner(System.in);

        System.out.println("=== SISTEMA DE MENSAGENS IFSC ===");
        System.out.print("Digite o nome de usuário que deseja usar: ");
        String meuNome = scanner.nextLine();

        try (Socket socket = new Socket(IP_BROKER, PORTA_BROKER)) {
            ObjectOutputStream out = new ObjectOutputStream(socket.getOutputStream());
            ObjectInputStream in = new ObjectInputStream(socket.getInputStream());

            out.writeObject(new Mensagem(Mensagem.TipoAcao.IDENTIFICAR, "", "", meuNome));
            out.flush();

            Thread ouvinte = new Thread(() -> {
                try {
                    while (true) {
                        Mensagem msgRecebida = (Mensagem) in.readObject();
                        System.out.println("\n\n[MENSAGEM EM " + msgRecebida.getTopico() + "]");
                        System.out.println("De: " + msgRecebida.getRemetente());
                        System.out.println("Conteúdo: " + msgRecebida.getPayload());
                        System.out.print("Escolha uma opção: ");
                    }
                } catch (Exception e) {
                    System.out.println("\nConexão com o broker encerrada.");
                }
            });
            ouvinte.start();

            while (true) {
                System.out.println("\n--- MENU MQTT (Usuário: " + meuNome + ") ---");
                System.out.println("1. Criar Tópico");
                System.out.println("2. Inscrever-se (Subscribe)");
                System.out.println("3. Publicar (Publish)");
                System.out.println("4. Desinscrever-se (Unsubscribe)");
                System.out.print("Escolha uma opção: ");
                
                String opcao = scanner.nextLine();
                String topico;

                switch (opcao) {
                    case "1":
                        System.out.print("Nome do tópico: ");
                        topico = scanner.nextLine();
                        out.writeObject(new Mensagem(Mensagem.TipoAcao.CRIAR_TOPICO, topico, "", meuNome));
                        break;
                    case "2":
                        System.out.print("Tópico para inscrição: ");
                        topico = scanner.nextLine();
                        out.writeObject(new Mensagem(Mensagem.TipoAcao.SUBSCRIBE, topico, "", meuNome));
                        break;
                    case "3":
                        System.out.print("Tópico alvo: ");
                        topico = scanner.nextLine();
                        System.out.print("Sua mensagem: ");
                        String payload = scanner.nextLine();
                        out.writeObject(new Mensagem(Mensagem.TipoAcao.PUBLISH, topico, payload, meuNome));
                        break;
                    case "4":
                        System.out.print("Tópico para sair: ");
                        topico = scanner.nextLine();
                        out.writeObject(new Mensagem(Mensagem.TipoAcao.UNSUBSCRIBE, topico, "", meuNome));
                        break;
                }
                out.flush();
            }
        } catch (IOException e) {
            System.err.println("Erro ao conectar no Broker.");
        }
    }
}