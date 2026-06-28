import java.io.Serializable;

public class Mensagem implements Serializable {
    private static final long serialVersionUID = 1L;

    public enum TipoAcao {
        IDENTIFICAR,
        CRIAR_TOPICO,
        SUBSCRIBE,
        PUBLISH,
        UNSUBSCRIBE,
        ACK,
        SOLICITAR_CERTIFICADO,
        CHAVE_SESSAO,
        MENSAGEM_CIFRADA_CANAL
    }

    private TipoAcao acao;
    private String topico;
    private String payload;
    private String remetente;
    private byte[] assinatura;
    private byte[] payloadCifradoPontaAPonta;
    private byte[] dadosCifradosCanal;
    
    // --- NOVO: Campo para transportar o IV de cada mensagem cifrada no canal ---
    private byte[] ivCanal;

    public Mensagem(TipoAcao acao, String topico, String payload, String remetente) {
        this.acao = acao;
        this.topico = topico;
        this.payload = payload;
        this.remetente = remetente;
    }

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

    public byte[] getPayloadCifradoPontaAPonta() { return payloadCifradoPontaAPonta; }
    public void setPayloadCifradoPontaAPonta(byte[] payloadCifradoPontaAPonta) { this.payloadCifradoPontaAPonta = payloadCifradoPontaAPonta; }

    public byte[] getDadosCifradosCanal() { return dadosCifradosCanal; }
    public void setDadosCifradosCanal(byte[] dadosCifradosCanal) { this.dadosCifradosCanal = dadosCifradosCanal; }

    public byte[] getIvCanal() { return ivCanal; }
    public void setIvCanal(byte[] ivCanal) { this.ivCanal = ivCanal; }
}