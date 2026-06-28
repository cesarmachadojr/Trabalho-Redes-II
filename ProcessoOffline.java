import java.io.*;
import java.security.*;
import java.security.cert.Certificate;

public class ProcessoOffline {
    public static void main(String[] args) {
        try {
            System.out.println("=== CONFIGURAÇÃO OFFLINE DE CREDENCIAIS DOS CLIENTES ===");

            // 1. Carrega o KeyStore que você gerou no terminal
            File keystoreFile = new File("broker.keystore");
            if (!keystoreFile.exists()) {
                System.err.println("[ERRO] Arquivo 'broker.keystore' não encontrado!");
                System.err.println("Por favor, execute os comandos do 'keytool' no terminal primeiro para gerar o Keystore e o .csr!");
                return;
            }

            char[] senhaKeystore = "123456".toCharArray();
            KeyStore ks = KeyStore.getInstance("JKS");
            try (FileInputStream fis = new FileInputStream(keystoreFile)) {
                ks.load(fis, senhaKeystore);
            }

            // 2. Recupera a Chave Privada do Broker de dentro do KeyStore para assinar os clientes
            PrivateKey chavePrivadaBroker = (PrivateKey) ks.getKey("broker", senhaKeystore);

            // 3. Gera as assinaturas digitais locais dos clientes para a autenticação mútua de testes
            String[] clientesPermitidos = {"Alice", "Bob", "Enilda"};

            for (String cliente : clientesPermitidos) {
                Signature rsa = Signature.getInstance("SHA256withRSA");
                rsa.initSign(chavePrivadaBroker);
                rsa.update(cliente.getBytes());
                byte[] assinatura = rsa.sign();

                // Salva a assinatura digital local para o cliente usar no ClienteGUI
                try (ObjectOutputStream out = new ObjectOutputStream(new FileOutputStream(cliente + ".assinatura"))) {
                    out.writeObject(assinatura);
                }
                System.out.println("[OK] Assinatura gerada e salva para o cliente: " + cliente);
            }

            System.out.println("\n=== PROCESSO CONCLUÍDO ===");
            System.out.println("1. Envie o arquivo 'broker.csr' gerado pelo terminal para o prof. Robson.");
            System.out.println("2. Quando ele devolver o certificado, salvaremos dentro do 'broker.keystore'.");

        } catch (Exception e) {
            System.err.println("Erro no processo offline:");
            e.printStackTrace();
        }
    }
}