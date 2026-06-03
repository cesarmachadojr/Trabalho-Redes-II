import java.io.*;
import java.security.*;

public class ProcessoOffline {
    public static void main(String[] args) {
        try {
            System.out.println("Iniciando processo offline de geracao de chaves...");

            // 1. Gera o par de chaves RSA do Servidor (Broker)
            KeyPairGenerator keyGen = KeyPairGenerator.getInstance("RSA");
            keyGen.initialize(2048);
            KeyPair parChavesServidor = keyGen.generateKeyPair();

            // 2. Salva a Chave Pública do Servidor em um arquivo
            try (ObjectOutputStream out = new ObjectOutputStream(new FileOutputStream("servidor.pub"))) {
                out.writeObject(parChavesServidor.getPublic());
            }
            System.out.println("Chave publica do servidor salva em 'servidor.pub'");

            // 3. Lista de clientes permitidos (Modifique os nomes conforme desejar seus testes)
            String[] clientesPermitidos = {"Alice", "Bob", "cesar", "putao"};

            for (String cliente : clientesPermitidos) {
                // Assina o nome do cliente com a Chave Privada do servidor
                Signature rsa = Signature.getInstance("SHA256withRSA");
                rsa.initSign(parChavesServidor.getPrivate());
                rsa.update(cliente.getBytes());
                byte[] assinatura = rsa.sign();

                // Salva a assinatura digital RSA em um arquivo local para o cliente
                try (ObjectOutputStream out = new ObjectOutputStream(new FileOutputStream(cliente + ".assinatura"))) {
                    out.writeObject(assinatura);
                }
                System.out.println("Certificado assinado gerado para: " + cliente);
            }

            System.out.println("\nProcesso offline concluido com sucesso! Voce ja pode ligar o Broker.");

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}