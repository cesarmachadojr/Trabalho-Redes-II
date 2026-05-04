import java.io.Serializable;

public class Mensagem implements Serializable {
    private static final long serialVersionUID = 1L;

    public enum TipoAcao {
        CRIAR_TOPICO,
        SUBSCRIBE,
        PUBLISH
    }

    private TipoAcao acao;
    private String topico;
    private String payload; // O conteúdo da mensagem

    public Mensagem(TipoAcao acao, String topico, String payload) {
        this.acao = acao;
        this.topico = topico;
        this.payload = payload;
    }

    public TipoAcao getAcao() { return acao; }
    public String getTopico() { return topico; }
    public String getPayload() { return payload; }
}