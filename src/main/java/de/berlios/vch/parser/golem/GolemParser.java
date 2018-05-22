package de.berlios.vch.parser.golem;

import java.io.IOException;
import java.io.StringReader;
import java.net.URI;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.ws.WebServiceException;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;

import org.apache.felix.ipojo.annotations.Component;
import org.apache.felix.ipojo.annotations.Invalidate;
import org.apache.felix.ipojo.annotations.Provides;
import org.apache.felix.ipojo.annotations.Requires;
import org.apache.felix.ipojo.annotations.Validate;
import org.osgi.service.log.LogService;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import de.berlios.vch.http.client.HttpUtils;
import de.berlios.vch.parser.IOverviewPage;
import de.berlios.vch.parser.IVideoPage;
import de.berlios.vch.parser.IWebPage;
import de.berlios.vch.parser.IWebParser;
import de.berlios.vch.parser.OverviewPage;
import de.berlios.vch.parser.VideoPage;
import de.berlios.vch.parser.WebPageTitleComparator;

@Component
@Provides
public class GolemParser implements IWebParser {

    public static final String ID = GolemParser.class.getName();
    protected static final String TEST_KEY = "7ad11d0bf90b659eff21bc61f0626196";
    protected static final String APP_KEY = "0eeb658b5b5ab1446cb771601e810df0";
    protected final String API_KEY = APP_KEY;

    protected final String BASE_URL = "http://api.golem.de/api/video";
    protected final String URL_PARAMS = "/?key=" + API_KEY + "&format=xml";
    protected final String LATEST_URL = BASE_URL + "/latest/50" + URL_PARAMS;
    protected final String POPULAR_URL = BASE_URL + "/top/50" + URL_PARAMS;
    protected final String META_URL = BASE_URL + "/meta/{video}" + URL_PARAMS;

    public static final String CHARSET = "utf-8";

    public static Map<String, String> HTTP_HEADERS = new HashMap<String, String>();
    static {
        HTTP_HEADERS.put("User-Agent", "Mozilla/5.0 (X11; U; Linux i686; en-US; rv:1.9.1.2) Gecko/20090821 Gentoo Firefox/3.6.3");
        HTTP_HEADERS.put("Accept-Language", "de-de,de;q=0.8,en-us;q=0.5,en;q=0.3");
    }

    @Requires
    private LogService logger;

    @Override
    public IOverviewPage getRoot() throws Exception {
        OverviewPage page = new OverviewPage();
        page.setParser(ID);
        page.setTitle(getTitle());
        page.setUri(new URI("vchpage://localhost/" + getId()));

        // add the top and latest pages
        OverviewPage latestPage = new OverviewPage();
        latestPage.setParser(ID);
        latestPage.setTitle("Neueste Videos");
        latestPage.setUri(new URI(LATEST_URL));
        page.getPages().add(latestPage);
        OverviewPage popularPage = new OverviewPage();
        popularPage.setParser(ID);
        popularPage.setTitle("Top Videos");
        popularPage.setUri(new URI(POPULAR_URL));
        page.getPages().add(popularPage);

        Collections.sort(page.getPages(), new WebPageTitleComparator());
        return page;
    }

    @Override
    public String getTitle() {
        return "Golem Videos";
    }

    @Override
    public IWebPage parse(IWebPage page) throws Exception {
        if (page instanceof IVideoPage) {
            parseVideoPage((IVideoPage) page);
        } else {
            String xml = HttpUtils.get(page.getUri().toString(), HTTP_HEADERS, CHARSET);
            IOverviewPage opage = (IOverviewPage) page;
            opage.getPages().clear();
            opage.getPages().addAll(parseVideoList(xml));
        }
        return page;
    }

    @Validate
    public void start() {
    }

    @Invalidate
    public void stop() {
    }

    @Override
    public String getId() {
        return ID;
    }

    private List<IVideoPage> parseVideoList(String xml) {
        List<IVideoPage> list = new ArrayList<IVideoPage>();
        try {
            DocumentBuilder builder = DocumentBuilderFactory.newInstance().newDocumentBuilder();
            Document doc = builder.parse(new InputSource(new StringReader(xml)));

            String success = getString(xml, "/golemderesult/success");
            if (!"1".equals(success)) {
                String errorMsg = getString(xml, "/golemderesult/errorMessage");
                throw new WebServiceException(errorMsg);
            }

            NodeList records = doc.getElementsByTagName("record");
            for (int i = 0; i < records.getLength(); i++) {
                VideoPage videoPage = new VideoPage();
                videoPage.setParser(ID);

                Node record = records.item(i);

                // parse the meta data uri
                String videoId = getString(record, "videoid");
                String uri = META_URL.replaceAll("\\{video\\}", videoId);
                videoPage.setUri(new URI(uri));

                // parse the title
                videoPage.setTitle(getString(record, "title"));

                list.add(videoPage);
            }
        } catch (Exception e) {
            logger.log(LogService.LOG_ERROR, "Couldn't parse result", e);
        }
        return list;
    }

    private void parseVideoPage(IVideoPage page) throws IOException {
        // don't parse a page twice
        if (page.getVideoUri() != null) {
            return;
        }

        // parse the meta data
        String xml = HttpUtils.get(page.getUri().toString(), HTTP_HEADERS, CHARSET);
        try {
            String success = getString(xml, "/golemderesult/success");
            if (!"1".equals(success)) {
                String errorMsg = getString(xml, "/golemderesult/errorMessage");
                throw new WebServiceException(errorMsg);
            }

            // parse the real page url
            page.setUri(new URI(getString(xml, "/golemderesult/data/pageurl")));

            // parse the duration
            String playtime = null;
            try {
                playtime = getString(xml, "/golemderesult/data/playtime");
                playtime = playtime.substring(0, playtime.indexOf('.'));
                page.setDuration(Integer.parseInt(playtime));
            } catch (Exception e) {
                logger.log(LogService.LOG_WARNING, "Couldn't parse video duration " + playtime, e);
            }

            // set publish date to now
            page.setPublishDate(Calendar.getInstance());

            // parse the video url
            // TODO qualität konfigurierbar machen
            String videoUri = getString(xml, "/golemderesult/data/high/videourl");
            page.setVideoUri(new URI(videoUri));

            // parse the thumbnail
            String thumbUri = getString(xml, "/golemderesult/data/high/image/url");
            page.setThumbnail(new URI(thumbUri));
        } catch (Exception e) {
            logger.log(LogService.LOG_ERROR, "Couldn't parse result", e);
        }
    }

    private String getString(String xml, String xpath) throws XPathExpressionException {
        XPath xp = XPathFactory.newInstance().newXPath();
        return xp.evaluate(xpath, new InputSource(new StringReader(xml)));
    }

    private String getString(Node node, String xpath) throws XPathExpressionException {
        XPath xp = XPathFactory.newInstance().newXPath();
        return xp.evaluate(xpath, node);
    }
}