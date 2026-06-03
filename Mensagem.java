import java.io.Serializable;

public class Mensagem implements Serializable {
    private static final long serialVersionUID = 1L;

    public enum TipoAcao {
        IDENTIFICAR,
        CRIAR_TOPICO,
        SUBSCRIBE,
        PUBLISH,
        UNSUBSCRIBE,
        ACK // --- CORREÇÃO 1: Confirmação do broker para CRIAR_TOPICO e SUBSCRIBE ---
    }

    private TipoAcao acao;
    private String topico;
    private String payload;
    private String remetente;

    // --- AUTENTICAÇÃO: Atributo para transportar os bytes da assinatura digital RSA ---
    private byte[] assinatura;

    // Construtor padrão (mensagens comuns de tópicos e chat)
    public Mensagem(TipoAcao acao, String topico, String payload, String remetente) {
        this.acao = acao;
        this.topico = topico;
        this.payload = payload;
        this.remetente = remetente;
        this.assinatura = null;
    }

    // Construtor sobrecarregado usado no IDENTIFICAR para enviar a assinatura
    public Mensagem(TipoAcao acao, String topico, String payload, String remetente, byte[] assinatura) {
        this.acao = acao;
        this.topico = topico;
        this.payload = payload;
        this.remetente = remetente;
        this.assinatura = assinatura;
    }

    public TipoAcao getAcao()      { return acao; }
    public String getTopico()      { return topico; }
    public String getPayload()     { return payload; }
    public String getRemetente()   { return remetente; }
    public byte[] getAssinatura()  { return assinatura; }
}
